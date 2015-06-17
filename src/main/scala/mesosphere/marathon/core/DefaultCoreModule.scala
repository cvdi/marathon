package mesosphere.marathon.core

import akka.actor.ActorSystem
import com.google.inject.Inject
import mesosphere.marathon.core.task.tracker.KillOverdueStagedTasksActor
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder }
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.core.base.actors.ActorsModule
import mesosphere.marathon.core.base.{ Clock, ShutdownHooks }
import mesosphere.marathon.core.flow.FlowActors
import mesosphere.marathon.core.launcher.LauncherModule
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerModule
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks.{ TaskFactory, TaskTracker }

import scala.util.Random

/**
  * Provides the wiring for the core module.
  *
  * Its parameters represent guice wired dependencies.
  * [[CoreGuiceModule]] exports some dependencies back to guice.
  */
class DefaultCoreModule @Inject() (
    // external dependencies still wired by guice
    marathonConf: MarathonConf,
    metrics: Metrics,
    actorSystem: ActorSystem,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskFactory: TaskFactory,
    leaderInfo: LeaderInfo) extends CoreModule {

  // INFRASTRUCTURE LAYER

  override lazy val clock = Clock()
  private[this] lazy val random = Random
  private[this] lazy val shutdownHookModule = ShutdownHooks()
  private[this] lazy val actorsModule = new ActorsModule(shutdownHookModule, actorSystem)

  // CORE

  lazy val leadershipModule = new LeadershipModule(actorsModule.actorRefFactory)
  override lazy val taskBusModule = TaskBusModule()

  private[this] lazy val offerMatcherManagerModule = new OfferMatcherManagerModule(
    // infrastructure
    clock, random, metrics, marathonConf,
    leadershipModule
  )

  override lazy val launcherModule = new LauncherModule(
    // infrastructure
    clock, metrics, marathonConf,

    // external guicedependencies
    marathonSchedulerDriverHolder,

    // internal core dependencies
    offerMatcherManagerModule.globalOfferMatcher)

  override lazy val appOfferMatcherModule = new LaunchQueueModule(
    leadershipModule, clock,

    // internal core dependencies
    offerMatcherManagerModule.subOfferMatcherManager,
    taskBusModule.taskStatusObservables,

    // external guice dependencies
    appRepository,
    taskTracker,
    taskFactory
  )

  // MAINTENANCE TASKS

  leadershipModule.startWhenLeader(
    KillOverdueStagedTasksActor.props(taskTracker, marathonSchedulerDriverHolder),
    "killOverdueStagedTasks")

  // FLOW CONTROL GLUE

  private[this] val flowActors = new FlowActors(leadershipModule)
  flowActors.refillOfferMatcherLaunchTokensForEveryStagedTask(
    marathonConf, taskBusModule.taskStatusObservables, offerMatcherManagerModule.subOfferMatcherManager)
  flowActors.reviveOffersWhenOfferMatcherManagerSignalsInterest(
    clock, marathonConf,
    offerMatcherManagerModule.globalOfferMatcherWantsOffers, marathonSchedulerDriverHolder)
}
