package mesosphere.marathon.core.launcher

import org.apache.mesos.Protos.Offer

import scala.concurrent.Future

trait OfferProcessor {
  def processOffer(offer: Offer): Future[Unit]
}
