package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ ActorContext, Stash, Cancellable, Actor, ActorLogging, ActorRef, Props }
import akka.event.LoggingReceive
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskCount
import mesosphere.marathon.core.launchqueue.impl.AppTaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.OfferMatcher
import mesosphere.marathon.core.matcher.OfferMatcher.{ MatchedTasks, TaskWithSource }
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.matcher.util.ActorTaskLaunchSource.TaskLaunchNotification
import mesosphere.marathon.core.matcher.util.{ ActorTaskLaunchSource, ActorOfferMatcher }
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusObservables }
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.tasks.TaskFactory.CreatedTask
import mesosphere.marathon.tasks.{ TaskFactory, TaskTracker }
import org.apache.mesos.Protos.{ TaskInfo, TaskID }
import rx.lang.scala.Subscription

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import akka.pattern.pipe

import scala.util.control.NonFatal

private[launchqueue] object AppTaskLauncherActor {
  def props(
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskFactory: TaskFactory,
    taskStatusObservable: TaskStatusObservables,
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef)(
      app: AppDefinition,
      initialCount: Int): Props = {
    Props(new AppTaskLauncherActor(
      offerMatcherManager,
      clock, taskFactory, taskStatusObservable, taskTracker, rateLimiterActor,
      app, initialCount))
  }

  sealed trait Requests

  /**
    * Increase the task count of the receiver.
    * The actor responds with a [[QueuedTaskCount]] message.
    */
  case class AddTasks(app: AppDefinition, count: Int) extends Requests
  /**
    * Get the current count.
    * The actor responds with a [[QueuedTaskCount]] message.
    */
  case object GetCount extends Requests

  /**
    * Results in rechecking whether we may launch tasks.
    */
  private case object RecheckIfBackOffUntilReached extends Requests

  case object Stop extends Requests
}

/**
  * Allows processing offers for starting tasks for the given app.
  */
private class AppTaskLauncherActor(
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskFactory: TaskFactory,
    taskStatusObservable: TaskStatusObservables,
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef,

    private[this] var app: AppDefinition,
    private[this] var tasksToLaunch: Int) extends Actor with ActorLogging with Stash {

  private[this] var inFlightTaskLaunches = Set.empty[TaskID]
  private[this] var backOffUntil: Option[Timestamp] = None

  /** Receive task updates to keep task list up-to-date. */
  private[this] var taskStatusUpdateSubscription: Subscription = _
  /** Currently known running tasks or tasks that we have requested to be launched. */
  private[this] var runningTasks: Set[MarathonTask] = _
  /** Like runningTasks but indexed by the taskId String */
  private[this] var runningTasksMap: Map[String, MarathonTask] = _

  /** Decorator to use this actor as a [[mesosphere.marathon.core.matcher.OfferMatcher#TaskLaunchSource]] */
  private[this] val myselfAsLaunchSource = ActorTaskLaunchSource(self)

  override def preStart(): Unit = {
    super.preStart()

    log.info("Started appTaskLaunchActor for {} version {} with initial count {}",
      app.id, app.version, tasksToLaunch)

    taskStatusUpdateSubscription = taskStatusObservable.forAppId(app.id).subscribe { update =>
      log.debug("update {}", update)
      self ! update
    }
    runningTasks = taskTracker.get(app.id)
    runningTasksMap = runningTasks.map(task => task.getId -> task).toMap

    rateLimiterActor ! RateLimiterActor.GetDelay(app)
  }

  override def postStop(): Unit = {
    taskStatusUpdateSubscription.unsubscribe()
    OfferMatcherRegistration.unregister()

    super.postStop()

    log.info("Stopped appTaskLaunchActor for {} version {}", app.id, app.version)
  }

  override def receive: Receive = waitForInitialDelay

  private[this] def waitForInitialDelay: Receive = LoggingReceive.withLabel("waitingForInitialDelay") {
    case RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp == app =>
      stash()
      unstashAll()
      context.become(active)
    case message: Any => stash()
  }

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveTaskStatusUpdate,
      receiveGetCurrentCount,
      receiveAddCount,
      receiveProcessOffers
    ).reduce(_.orElse[Any, Unit](_))
  }

  private[this] def receiveWaitingForInFlight: Receive = LoggingReceive.withLabel("waitingForInFlight") {
    case notification: TaskLaunchNotification =>
      receiveTaskLaunchNotification(notification)
      waitingForInFlight()

    case "waitingForInFlight" => sender() ! "waitingForInFlight" // for testing
  }

  private[this] def receiveStop: Receive = {
    case AppTaskLauncherActor.Stop => waitingForInFlight()
  }

  private[this] def waitingForInFlight(): Unit = {
    if (inFlightTaskLaunches.isEmpty) {
      context.stop(self)
    }
    else {
      context.become(receiveWaitingForInFlight)
    }
  }

  /**
    * Receive rate limiter updates.
    */
  private[this] def receiveDelayUpdate: Receive = {
    case RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp == app =>
      if (backOffUntil.forall(_ < delayUntil)) {
        import context.dispatcher
        val now: Timestamp = clock.now()
        context.system.scheduler.scheduleOnce(now until delayUntil, self, RecheckIfBackOffUntilReached)
      }

      backOffUntil = Some(delayUntil)
      OfferMatcherRegistration.manageOfferMatcherStatus()
      log.info("{}", status)

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case ActorTaskLaunchSource.TaskLaunchRejected(taskInfo, reason) if inFlightTaskLaunches(taskInfo.getTaskId) =>
      // This task is not yet known to mesos, so there will be no event that removes
      // it automatically from the taskTracker.
      taskTracker.terminated(app.id, taskInfo.getTaskId.getValue)
      removeTask(taskInfo.getTaskId)
      tasksToLaunch += 1
      log.info(
        "Task launch for '{}' was denied, reason '{}', rescheduling. {}",
        taskInfo.getTaskId.getValue, reason, status)
      OfferMatcherRegistration.manageOfferMatcherStatus()

    case ActorTaskLaunchSource.TaskLaunchRejected(taskInfo, reason) => // ignore

    case ActorTaskLaunchSource.TaskLaunchAccepted(taskInfo) =>
      inFlightTaskLaunches -= taskInfo.getTaskId
      log.info("Task launch for '{}' was accepted. {}", taskInfo.getTaskId.getValue, status)
  }

  private[this] def receiveTaskStatusUpdate: Receive = {
    case TaskStatusUpdate(_, taskId, MarathonTaskStatus.Terminal(_)) =>
      log.debug("task '{}' finished", taskId.getValue)
      removeTask(taskId)

    case TaskStatusUpdate(_, taskId, _) => log.debug("ignore update of task '{}'", taskId.getValue)
  }

  private[this] def removeTask(taskId: TaskID): Unit = {
    inFlightTaskLaunches -= taskId
    runningTasksMap.get(taskId.getValue).foreach { marathonTask =>
      runningTasksMap -= taskId.getValue
      runningTasks -= marathonTask
    }
  }

  private[this] def receiveGetCurrentCount: Receive = {
    case AppTaskLauncherActor.GetCount =>
      replyWithQueuedTaskCount()
  }

  private[this] def receiveAddCount: Receive = {
    case AppTaskLauncherActor.AddTasks(newApp, addCount) =>
      if (app != newApp) {
        app = newApp
        log.info("getting new app definition for '{}', version {}", app.id, app.version)
      }

      tasksToLaunch += addCount
      OfferMatcherRegistration.manageOfferMatcherStatus()

      replyWithQueuedTaskCount()
  }

  private[this] def replyWithQueuedTaskCount(): Unit = {
    sender() ! QueuedTaskCount(
      app,
      tasksLeftToLaunch = tasksToLaunch,
      taskLaunchesInFlight = inFlightTaskLaunches.size,
      tasksLaunchedOrRunning = runningTasks.size - inFlightTaskLaunches.size,
      backOffUntil.getOrElse(clock.now())
    )
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(deadline, offer) if clock.now() >= deadline || !shouldLaunchTasks =>
      val deadlineReached = clock.now() >= deadline
      log.debug("ignoring offer, offer deadline {}reached. {}", if (deadlineReached) "" else "NOT ", status)
      sender ! MatchedTasks(offer.getId, Seq.empty)

    case ActorOfferMatcher.MatchOffer(deadline, offer) =>
      val newTaskOpt: Option[CreatedTask] = taskFactory.newTask(app, offer, runningTasks)
      newTaskOpt match {
        case Some(CreatedTask(mesosTask, marathonTask)) =>
          def updateActorState(): Unit = {
            runningTasks += marathonTask
            runningTasksMap += marathonTask.getId -> marathonTask
            inFlightTaskLaunches += mesosTask.getTaskId
            tasksToLaunch -= 1
            OfferMatcherRegistration.manageOfferMatcherStatus()
          }
          def saveTask(): Future[Seq[TaskInfo]] = {
            taskTracker.created(app.id, marathonTask)
            val context = this.context
            import context.dispatcher
            taskTracker
              .store(app.id, marathonTask)
              .map { _ =>
                val reject = ActorTaskLaunchSource.TaskLaunchRejected(
                  mesosTask, "timeout: no accept received within 3 seconds")
                scheduleTaskLaunchTimeout(context, reject)
                Seq(mesosTask)
              }.recover {
                case NonFatal(e) =>
                  log.error(e, "While storing task '{}'", mesosTask.getTaskId.getValue)
                  self ! ActorTaskLaunchSource.TaskLaunchRejected(mesosTask, "could not save task")
                  Seq.empty
              }
          }

          log.info("Request to launch task with id '{}', version '{}'. {}",
            mesosTask.getTaskId.getValue, app.version, status)

          updateActorState()

          import context.dispatcher
          saveTask()
            .map(mesosTasks => MatchedTasks(offer.getId, mesosTasks.map(TaskWithSource(myselfAsLaunchSource, _))))
            .pipeTo(sender())

        case None => sender() ! MatchedTasks(offer.getId, Seq.empty)
      }
  }

  protected def scheduleTaskLaunchTimeout(
    context: ActorContext,
    message: ActorTaskLaunchSource.TaskLaunchRejected): Cancellable =
    {
      import context.dispatcher
      context.system.scheduler.scheduleOnce(3.seconds, self, message)
    }

  private[this] def backoffActive: Boolean = backOffUntil.forall(_ > clock.now())
  private[this] def shouldLaunchTasks: Boolean = tasksToLaunch > 0 && !backoffActive

  private[this] def status: String = {
    val backoffStr = backOffUntil match {
      case Some(backOffUntil) if backOffUntil > clock.now() => s"currently waiting for backoff($backOffUntil)"
      case _ => "not backing off"
    }

    s"$tasksToLaunch tasksToLaunch, ${inFlightTaskLaunches.size} in flight. $backoffStr"
  }

  /** Manage registering this actor as offer matcher. Only register it if tasksToLaunch > 0. */
  private[this] object OfferMatcherRegistration {
    private[this] val myselfAsOfferMatcher: OfferMatcher = new ActorOfferMatcher(clock, self)
    private[this] var registeredAsMatcher = false

    /** Register/unregister as necessary */
    def manageOfferMatcherStatus(): Unit = {
      val shouldBeRegistered = shouldLaunchTasks

      if (shouldBeRegistered && !registeredAsMatcher) {
        log.debug("Registering for {}, {}.", app.id, app.version)
        offerMatcherManager.addSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = true
      }
      else if (!shouldBeRegistered && registeredAsMatcher) {
        if (tasksToLaunch > 0) {
          log.info("Backing off due to task failures. Stop receiving offers for {}, {}", app.id, app.version)
        }
        else {
          log.info("No tasks left to launch. Stop receiving offers for {}, {}", app.id, app.version)
        }
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
      }
    }

    def unregister(): Unit = {
      if (registeredAsMatcher) {
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
      }
    }
  }
}
