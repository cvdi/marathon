package mesosphere.marathon.core.matcher.util

import akka.actor.ActorRef
import mesosphere.marathon.core.matcher.OfferMatcher.TaskLaunchSource
import mesosphere.marathon.core.matcher.util.ActorTaskLaunchSource.{ TaskLaunchRejected, TaskLaunchAccepted }
import org.apache.mesos.Protos.TaskInfo

private class ActorTaskLaunchSource(actorRef: ActorRef) extends TaskLaunchSource {
  override def taskLaunchAccepted(taskInfo: TaskInfo): Unit = actorRef ! TaskLaunchAccepted(taskInfo)
  override def taskLaunchRejected(taskInfo: TaskInfo, reason: String): Unit =
    actorRef ! TaskLaunchRejected(taskInfo, reason)
}

object ActorTaskLaunchSource {
  def apply(actorRef: ActorRef): TaskLaunchSource = new ActorTaskLaunchSource(actorRef)

  sealed trait TaskLaunchNotification {
    def taskInfo: TaskInfo
  }
  object TaskLaunchNotification {
    def unapply(notification: TaskLaunchNotification): Option[TaskInfo] = Some(notification.taskInfo)
  }
  case class TaskLaunchAccepted(taskInfo: TaskInfo) extends TaskLaunchNotification
  case class TaskLaunchRejected(taskInfo: TaskInfo, reason: String) extends TaskLaunchNotification
}
