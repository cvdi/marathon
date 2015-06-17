package mesosphere.marathon.core.matcher.manager

import org.rogach.scallop.ScallopConf

trait OfferMatcherConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val maxTasksPerOffer = opt[Int]("max_tasks_per_offer",
    descr = "Maximum tasks per offer. Do not start more than this number of tasks on a single offer.",
    default = Some(100))

}
