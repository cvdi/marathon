package mesosphere.marathon.core.flow

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.impl.{ OfferMatcherLaunchTokensActor, ReviveOffersActor }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskStatusObservables
import rx.lang.scala.Observable

class FlowActors(leadershipModule: LeadershipModule) {
  def reviveOffersWhenOfferMatcherManagerSignalsInterest(
    clock: Clock, conf: ReviveOffersConfig,
    offersWanted: Observable[Boolean], driverHolder: MarathonSchedulerDriverHolder): Unit = {
    lazy val reviveOffersActor = ReviveOffersActor.props(
      clock, conf,
      offersWanted, driverHolder
    )
    leadershipModule.startWhenLeader(reviveOffersActor, "reviveOffersWhenWanted")
  }

  def refillOfferMatcherLaunchTokensForEveryStagedTask(
    conf: LaunchTokenConfig,
    taskStatusObservables: TaskStatusObservables,
    offerMatcherManager: OfferMatcherManager): Unit =
    {
      lazy val offerMatcherLaunchTokensProps = OfferMatcherLaunchTokensActor.props(
        conf, taskStatusObservables, offerMatcherManager
      )
      leadershipModule.startWhenLeader(offerMatcherLaunchTokensProps, "offerMatcherLaunchTokens")

    }
}
