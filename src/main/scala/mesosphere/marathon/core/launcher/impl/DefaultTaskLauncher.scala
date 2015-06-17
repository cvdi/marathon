package mesosphere.marathon.core.launcher.impl

import java.util.Collections

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.TaskLauncher
import org.apache.mesos.Protos.{ OfferID, Status, TaskInfo }
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

private[launcher] class DefaultTaskLauncher(
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    clock: Clock) extends TaskLauncher {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def launchTasks(offerID: OfferID, taskInfos: Seq[TaskInfo]): Boolean = {
    marathonSchedulerDriverHolder.driver match {
      case Some(driver) =>
        import scala.collection.JavaConverters._
        val status = driver.launchTasks(Collections.singleton(offerID), taskInfos.asJava)
        log.info("status = {}", status)
        status == Status.DRIVER_RUNNING

      case None => false
    }
  }

  override def declineOffer(offerID: OfferID): Unit = {
    withDriver(s"declineOffer(${offerID.getValue})") {
      _.declineOffer(offerID)
    }
  }

  private[this] def withDriver(description: => String)(block: SchedulerDriver => Unit) = {
    marathonSchedulerDriverHolder.driver match {
      case Some(driver) => block(driver)
      case None         => log.warn(s"Cannot execute '$description', no driver available")
    }
  }
}
