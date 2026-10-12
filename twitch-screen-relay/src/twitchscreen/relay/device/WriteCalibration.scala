package twitchscreen.relay.device

/** A fresh actor-owned sample accompanies an acceptance only when it still identifies the accepted epoch. A reset between acceptance and
  * sampling must never calibrate an old result into the new clock domain.
  */
private[device] object WriteCalibration:
  def attach[A](value: A, acceptedEpoch: String, sampled: RelayClock): (A, Option[String], Option[String]) =
    if acceptedEpoch == sampled.epochHex then (value, Some(sampled.epochHex), Some(sampled.monotonicMs.toString))
    else (value, None, None)
