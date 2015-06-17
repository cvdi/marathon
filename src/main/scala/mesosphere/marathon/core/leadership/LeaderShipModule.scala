package mesosphere.marathon.core.leadership

import akka.actor.{ ActorRef, ActorRefFactory, Props }
import mesosphere.marathon.core.leadership.impl.{
  ActorLeadershipCoordinator,
  LeadershipCoordinationActor,
  WhenLeaderActor
}

class LeadershipModule(actorRefFactory: ActorRefFactory) {

  private[this] var whenLeaderRefs = Set.empty[ActorRef]
  private[this] var started: Boolean = false

  def startWhenLeader(props: => Props, name: String, considerPreparedOnStart: Boolean = true): ActorRef = {
    require(!started, "already started")
    val proxyProps = WhenLeaderActor.props(props)
    val actorRef = actorRefFactory.actorOf(proxyProps, name)
    whenLeaderRefs += actorRef
    actorRef
  }

  def coordinator(): LeadershipCoordinator = coordinator_

  private[this] lazy val coordinator_ = {
    require(!started, "already started")
    started = true

    val props = LeadershipCoordinationActor.props(whenLeaderRefs)
    val actorRef = actorRefFactory.actorOf(props, "leaderShipCoordinator")
    new ActorLeadershipCoordinator(actorRef)
  }
}
