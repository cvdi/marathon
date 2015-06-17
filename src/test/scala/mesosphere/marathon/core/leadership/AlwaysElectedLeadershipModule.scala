package mesosphere.marathon.core.leadership

import akka.actor.{ ActorRefFactory, ActorRef, Props }
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.core.base.actors.ActorsModule

private class AlwaysElectedLeadershipModule(actorRefFactory: ActorRefFactory) extends LeadershipModule(actorRefFactory) {
  override def startWhenLeader(props: => Props, name: String, preparedOnStart: Boolean = true): ActorRef =
    actorRefFactory.actorOf(props, name)
  override def coordinator(): LeadershipCoordinator = ???
}

object AlwaysElectedLeadershipModule {
  def apply(shutdownHooks: ShutdownHooks): LeadershipModule = {
    val actorsModule = new ActorsModule(shutdownHooks)
    new AlwaysElectedLeadershipModule(actorsModule.actorRefFactory)
  }
}
