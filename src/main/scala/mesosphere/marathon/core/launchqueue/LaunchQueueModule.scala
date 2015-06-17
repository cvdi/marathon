package mesosphere.marathon.core.launchqueue

import akka.actor.{ ActorRef, Props }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launchqueue.impl.{
  ActorLaunchQueue,
  AppTaskLauncherActor,
  LaunchQueueActor,
  RateLimiter,
  RateLimiterActor
}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskStatusObservables
import mesosphere.marathon.state.{ AppDefinition, AppRepository }
import mesosphere.marathon.tasks.{ TaskFactory, TaskTracker }

class LaunchQueueModule(
    leadershipModule: LeadershipModule,
    clock: Clock,
    subOfferMatcherManager: OfferMatcherManager,
    taskStatusObservables: TaskStatusObservables,
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskFactory: TaskFactory) {

  private[this] val taskQueueActorRef: ActorRef = {
    val props = LaunchQueueActor.props(appActorProps)
    leadershipModule.startWhenLeader(props, "launchQueue")
  }
  private[this] val rateLimiter: RateLimiter = new RateLimiter(clock)

  private[this] val rateLimiterActor: ActorRef = {
    val props = RateLimiterActor.props(
      rateLimiter, taskTracker, appRepository, taskQueueActorRef, taskStatusObservables)
    leadershipModule.startWhenLeader(props, "rateLimiter")
  }

  val taskQueue: LaunchQueue = new ActorLaunchQueue(taskQueueActorRef, rateLimiterActor)

  private[this] def appActorProps(app: AppDefinition, count: Int): Props =
    AppTaskLauncherActor.props(
      subOfferMatcherManager,
      clock,
      taskFactory,
      taskStatusObservables,
      taskTracker,
      rateLimiterActor)(app, count)
}
