package mesosphere.marathon.core.launcher

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.impl.{ DefaultOfferProcessor, DefaultTaskLauncher }
import mesosphere.marathon.core.matcher.OfferMatcher
import mesosphere.marathon.metrics.Metrics

class LauncherModule(
    clock: Clock,
    metrics: Metrics,
    offerProcessorConfig: OfferProcessorConfig,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    offerMatcher: OfferMatcher) {

  lazy val offerProcessor: OfferProcessor =
    new DefaultOfferProcessor(
      offerProcessorConfig, clock,
      metrics,
      offerMatcher, taskLauncher)

  lazy val taskLauncher: TaskLauncher = new DefaultTaskLauncher(
    marathonSchedulerDriverHolder,
    clock)
}
