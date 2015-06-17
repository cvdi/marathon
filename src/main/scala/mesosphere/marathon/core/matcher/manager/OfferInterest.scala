package mesosphere.marathon.core.matcher.manager

sealed trait OfferInterest

object OfferInterest {
  case object ReconsiderPreviouslyRejectedOffers extends OfferInterest
  case object StillInterested extends OfferInterest
  case object NotInterested extends OfferInterest
}
