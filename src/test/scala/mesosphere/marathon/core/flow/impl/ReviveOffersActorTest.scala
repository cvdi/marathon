package mesosphere.marathon.core.flow.impl

import akka.actor.{ Cancellable, ActorSystem, Scheduler }
import akka.testkit.TestActorRef
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec }
import org.apache.mesos.SchedulerDriver
import org.mockito.{ Matchers, ArgumentMatcher, Mockito }
import org.mockito.internal.matchers.{ Matches, CapturesArguments }
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject
import scala.concurrent.duration._

class ReviveOffersActorTest extends MarathonSpec {
  test("do not do anything") {
    actorRef.start()
  }

  test("revive on first true") {
    actorRef.start()
    offersWanted.onNext(true)

    Mockito.verify(driver).reviveOffers()
  }

  test("only one revive for two fast consecutive trues") {
    actorRef.start()
    offersWanted.onNext(true)
    Mockito.verify(driver).reviveOffers()
    offersWanted.onNext(true)
    assert(actorRef.underlyingActor.scheduled == Vector(5.seconds))
  }

  test("the third true reschedules") {
    actorRef.start()
    offersWanted.onNext(true)
    offersWanted.onNext(true)
    clock += 3.seconds
    offersWanted.onNext(true)

    Mockito.verify(driver).reviveOffers()
    Mockito.verify(actorRef.underlyingActor.cancellable).cancel()
    assert(actorRef.underlyingActor.scheduled == Vector(5.seconds, 2.seconds))
  }

  test("Check revives if last offersWanted == true and more than 5.seconds ago") {
    actorRef.start()
    offersWanted.onNext(true)

    offersWanted.onNext(false)
    offersWanted.onNext(true)
    assert(actorRef.underlyingActor.scheduled == Vector(5.seconds))

    clock += 5.seconds
    actorRef ! ReviveOffersActor.Check

    Mockito.verify(driver, Mockito.times(2)).reviveOffers()
    Mockito.verify(actorRef.underlyingActor.cancellable).cancel()
    assert(actorRef.underlyingActor.scheduled == Vector(5.seconds))
  }

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] val conf = new ReviveOffersConfig {}
  conf.afterInit()
  private[this] var clock: ConstantClock = _
  private[this] var actorRef: TestActorRef[TestableActor] = _
  private[this] var offersWanted: Subject[Boolean] = _
  private[this] var driver: SchedulerDriver = _
  private[this] var driverHolder: MarathonSchedulerDriverHolder = _
  private[this] var mockScheduler: Scheduler = _

  before {
    actorSystem = ActorSystem()
    clock = ConstantClock()
    offersWanted = PublishSubject()
    driver = mock[SchedulerDriver]
    driverHolder = new MarathonSchedulerDriverHolder
    driverHolder.driver = Some(driver)
    mockScheduler = mock[Scheduler]

    actorRef = TestActorRef(new TestableActor)
  }

  after {
    Mockito.verifyNoMoreInteractions(actorRef.underlyingActor.cancellable)

    actorSystem.shutdown()
    actorSystem.awaitTermination()

    Mockito.verifyNoMoreInteractions(driver)
    Mockito.verifyNoMoreInteractions(mockScheduler)
  }

  private class TestableActor extends ReviveOffersActor(clock, conf, offersWanted, driverHolder) {
    var scheduled = Vector.empty[FiniteDuration]
    var cancellable = mock[Cancellable]

    override protected def schedulerCheck(duration: FiniteDuration): Cancellable = {
      scheduled :+= duration
      cancellable
    }
  }
}
