package mesosphere.marathon.core.flow.impl

import akka.actor.{ Scheduler, Cancellable, Actor, ActorLogging, Props }
import akka.event.LoggingReceive
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.state.Timestamp
import rx.lang.scala.{ Observable, Subscription }
import scala.concurrent.duration._

private[flow] object ReviveOffersActor {
  def props(
    clock: Clock, conf: ReviveOffersConfig,
    offersWanted: Observable[Boolean], driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new ReviveOffersActor(clock, conf, offersWanted, driverHolder))
  }

  private[impl] case object Check
}

/**
  * Revive offers whenever interest is signaled but maximally every 5 seconds.
  */
private class ReviveOffersActor(
    clock: Clock, conf: ReviveOffersConfig,
    offersWanted: Observable[Boolean],
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with ActorLogging {
  private[this] var subscription: Subscription = _
  private[this] var previouslyWanted: Boolean = false
  private[this] var lastRevive: Timestamp = Timestamp(0)
  private[this] var nextReviveCancellableOpt: Option[Cancellable] = None

  override def preStart(): Unit = {
    subscription = offersWanted.subscribe(self ! _)
  }

  override def postStop(): Unit = {
    subscription.unsubscribe()
    nextReviveCancellableOpt.foreach(_.cancel())
    nextReviveCancellableOpt = None
  }

  private[this] def reviveOffers(): Unit = {
    previouslyWanted = true

    val now: Timestamp = clock.now()
    val nextRevive = lastRevive + conf.minReviveOffersInterval().milliseconds
    nextReviveCancellableOpt.foreach(_.cancel())
    nextReviveCancellableOpt = None

    if (nextRevive <= now) {
      log.debug("Revive offers")
      driverHolder.driver.foreach(_.reviveOffers())
      lastRevive = now
    }
    else {
      nextReviveCancellableOpt = Some(schedulerCheck(now until nextRevive))
    }
  }

  override def receive: Receive = LoggingReceive {
    case true                                        => reviveOffers()
    case ReviveOffersActor.Check if previouslyWanted => reviveOffers()
    case bool: Boolean                               => previouslyWanted = bool
  }

  protected def schedulerCheck(duration: FiniteDuration): Cancellable = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(duration, self, ReviveOffersActor.Check)
  }
}
