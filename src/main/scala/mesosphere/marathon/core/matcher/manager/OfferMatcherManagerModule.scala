package mesosphere.marathon.core.matcher.manager

import akka.actor.ActorRef
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.OfferMatcher
import mesosphere.marathon.core.matcher.manager.impl.{ ActorOfferMatcherManager, OfferMatcherManagerActor }
import mesosphere.marathon.core.matcher.util.ActorOfferMatcher
import mesosphere.marathon.metrics.Metrics
import rx.lang.scala.subjects.PublishSubject
import rx.lang.scala.{ Observable, Subject }

import scala.util.Random

class OfferMatcherManagerModule(
    clock: Clock, random: Random, metrics: Metrics,
    offerMatcherConfig: OfferMatcherConfig,
    leadershipModule: LeadershipModule) {

  private[this] lazy val offersWanted: Subject[Boolean] = PublishSubject[Boolean]()

  private[this] val offerMatcherMultiplexer: ActorRef = {
    val props = OfferMatcherManagerActor.props(random, clock, offerMatcherConfig, offersWanted)
    leadershipModule.startWhenLeader(props, "offerMatcherManager")
  }

  val globalOfferMatcherWantsOffers: Observable[Boolean] = offersWanted
  val globalOfferMatcher: OfferMatcher = new ActorOfferMatcher(clock, offerMatcherMultiplexer)
  val subOfferMatcherManager: OfferMatcherManager = new ActorOfferMatcherManager(offerMatcherMultiplexer)
}
