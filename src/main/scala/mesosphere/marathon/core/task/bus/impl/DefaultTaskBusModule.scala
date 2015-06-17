package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.task.bus.{ TaskStatusObservables, TaskStatusEmitter, TaskBusModule }

private[bus] class DefaultTaskBusModule extends TaskBusModule {
  override lazy val taskStatusEmitter: TaskStatusEmitter =
    new DefaultTaskStatusEmitter(internalTaskStatusEventStream)
  override lazy val taskStatusObservables: TaskStatusObservables =
    new DefaultTaskStatusObservables(internalTaskStatusEventStream)

  private[this] lazy val internalTaskStatusEventStream = new InternalTaskStatusEventStream()
}
