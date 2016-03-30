package mesosphere.marathon.upgrade

import akka.actor.{ ActorLogging, ActorRef, Actor }
import mesosphere.marathon.core.readiness.{ ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentManager.ReadinessCheckUpdate
import mesosphere.marathon.upgrade.ReadinessBehavior.ScheduleReadinessCheckFor
import rx.lang.scala.Subscription

/**
  * ReadinessBehavior makes sure all tasks are healthy and ready depending on the app definition.
  * Listens for TaskStatusUpdate events, HealthCheck events and ReadinessCheck events.
  * If a task becomes ready, the taskIsReady hook is called.
  *
  * Assumptions:
  *  - the actor is attached to the event stream for HealthStatusChanged and MesosStatusUpdateEvent
  */
trait ReadinessBehavior { this: Actor with ActorLogging =>

  import context.dispatcher

  //dependencies
  def app: AppDefinition
  def readinessCheckExecutor: ReadinessCheckExecutor
  def deploymentManager: ActorRef
  def taskTracker: TaskTracker
  def status: DeploymentStatus

  //computed values to have stable identifier in pattern matcher
  val appId: PathId = app.id
  val version: Timestamp = app.version
  val versionString: String = version.toString

  //state managed by this behavior
  var healthy = Set.empty[Task.Id]
  var ready = Set.empty[Task.Id]
  var subscriptions = Map.empty[String, Subscription]

  /**
    * Hook method which is called, whenever a task becomes ready according to the given app definition.
    * @param taskId the id of the task that has become ready.
    */
  def taskIsReady(taskId: Task.Id): Unit

  override def postStop(): Unit = {
    subscriptions.values.foreach(_.unsubscribe())
  }

  /**
    * Depending on the app definition, this method handles:
    * - app without health checks and without readiness checks
    * - app with health checks and without readiness checks
    * - app without health checks and with readiness checks
    * - app with health checks and with readiness checks
    *
    * The #taskIsReady function is called, when the task is ready according to the app definition.
    */
  //scalastyle:off cyclomatic.complexity
  def readinessBehavior: Receive = {
    def taskIsRunning(taskFn: Task.Id => Unit): Receive = {
      case MesosStatusUpdateEvent(slaveId, taskId, "TASK_RUNNING", _, `appId`, _, _, _, `versionString`, _, _) => taskFn(taskId) //scalastyle:ignore line.size.limit
    }

    def taskIsHealthy(taskFn: Task.Id => Unit): Receive = {
      case HealthStatusChanged(`appId`, taskId, `version`, true, _, _) if !healthy(taskId) => taskFn(taskId)
    }

    def startedTaskIsReady(taskId: Task.Id): Unit = {
      log.debug(s"Started task is ready: $taskId")
      healthy += taskId
      ready += taskId
      taskIsReady(taskId)
    }

    def initiateReadinessCheck(taskId: Task.Id): Unit = {
      log.debug(s"Initiate readiness check for task: $taskId")
      healthy += taskId
      val me = self
      taskTracker.task(taskId).map { taskOption =>
        for {
          task <- taskOption
          launched <- task.launched
        } me ! ScheduleReadinessCheckFor(task, launched)
      }
    }

    def subscriptionKey(id: Task.Id, name: String) = s"${id.toString}:$name"

    def readinessCheckBehavior: Receive = {
      case ScheduleReadinessCheckFor(task, launched) =>
        log.debug(s"Schedule readiness check for task: ${task.taskId}")
        ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(app, task, launched).foreach { spec =>
          val subscriptionName = subscriptionKey(task.taskId, spec.checkName)
          val subscription = readinessCheckExecutor.execute(spec).subscribe(self ! _)
          subscriptions += subscriptionName -> subscription
        }

      case result: ReadinessCheckResult =>
        log.info(s"Received readiness check update for task ${result.taskId} with ready: ${result.ready}")
        deploymentManager ! ReadinessCheckUpdate(status.plan.id, result)
        if (result.ready) {
          ready += result.taskId
          val subscriptionName = subscriptionKey(result.taskId, result.name)
          subscriptions.get(subscriptionName).foreach(_.unsubscribe())
          subscriptions -= subscriptionName
          taskIsReady(result.taskId)
        }
    }

    val readyFn: Task.Id => Unit = if (app.readinessChecks.isEmpty) startedTaskIsReady else initiateReadinessCheck
    val startBehavior = if (app.healthChecks.nonEmpty) taskIsHealthy(readyFn) else taskIsRunning(readyFn)
    val readinessBehavior = if (app.readinessChecks.nonEmpty) readinessCheckBehavior else Actor.emptyBehavior
    startBehavior orElse readinessBehavior
  }
}

object ReadinessBehavior {
  case class ScheduleReadinessCheckFor(task: Task, launched: Task.Launched)
}
