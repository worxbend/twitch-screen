package twitchscreen.relay.device

import twitchscreen.relay.http.ApiTime
import twitchscreen.relay.protocol.DeskMessageCodec

/** Bounded observation history owned by the same lock as hub capture. Reads never fabricate missed samples or replay a lossy bus. */
private[device] final class AdminChartHistory:
  private final case class Bucket(
      arrivals: Double = 0,
      viewers: Option[Double] = None,
      chat: Option[Double] = None,
      devices: Map[String, Vector[Option[Double]]] = Map.empty
  )
  private final case class Counter(identity: (Long, Long), revision: Long, totals: Vector[Long], boundaryBucket: Option[Long])
  private var epoch = 0L
  private var generation = 0L
  private var latestSeq = 0L
  private var buckets = Map.empty[Long, Bucket]
  private var counters = Map.empty[String, Counter]

  def capture(value: HubObservation): Charts_OUT =
    val publication = value.publication
    val now = publication.sampledRelayMs
    val key = now / 5000L
    val selected = value.devices.sortBy(device => (!device.connected, -device.lastSeenRelayMs, device.link.device.value)).take(16)
    val selectedIds = selected.map(_.link.device.value).toSet
    val source = publication.control.fold(1L)(_.sourceGeneration)
    if epoch != publication.relayEpoch then
      epoch = publication.relayEpoch
      generation = source
      latestSeq = publication.latestSeq.value
      buckets = Map.empty
      counters = Map.empty
    if source != generation then
      generation = source
      buckets = buckets.view.mapValues(_.copy(viewers = None, chat = None)).toMap
    buckets = buckets
      .filter((bucket, _) => bucket >= key - 179L)
      .map((bucket, row) => bucket -> row.copy(devices = row.devices.filter((id, _) => selectedIds.contains(id))))
    counters = counters.filter((id, _) => selectedIds.contains(id))
    val previous = buckets.getOrElse(key, Bucket())
    val arrivals = math.max(0L, publication.latestSeq.value - latestSeq).toDouble
    latestSeq = publication.latestSeq.value
    val statsFresh = publication.statsAvailability == DeskMessageCodec.Availability.Observed &&
      publication.statsObservedAt.exists(at => !at.isAfter(value.capturedAt) && value.capturedAt.toEpochMilli - at.toEpochMilli <= 15000L)
    val samples = selected.map: device =>
      val id = device.link.device.value
      val report = device.desk
        .flatMap(_.report)
        .filter(row =>
          device.connected && row.observedRelayLowerMs > 0 && now >= row.observedRelayLowerMs && now - row.observedRelayLowerMs <= 15000L
        )
      val data = report.fold(Vector.fill[Option[Double]](4)(None)): row =>
        val identity = (row.deviceBootId, row.identity.connectionId)
        val totals = Vector(row.expiredTotal, row.suppressedTotal, row.refusedTotal)
        val before = counters.get(id)
        val changed = before.exists(_.identity != identity)
        val boundary = if changed then Some(key) else before.flatMap(_.boundaryBucket)
        val deltas = before
          .filter(_.identity == identity)
          .map: old =>
            if row.revision == old.revision then Vector.fill[Option[Double]](3)(Some(0.0))
            else totals.zip(old.totals).map((current, prior) => Option.when(current >= prior)((current - prior).toDouble))
        counters = counters.updated(id, Counter(identity, row.revision, totals, boundary))
        if boundary.contains(key) then Vector.fill[Option[Double]](4)(None)
        else
          val prior = previous.devices.getOrElse(id, Vector.fill[Option[Double]](4)(None))
          val accumulated = deltas.fold(Vector.fill[Option[Double]](3)(None)): values =>
            values.zip(prior.drop(1)).map((delta, count) => delta.map(_ + count.getOrElse(0.0)))
          Vector(Some(row.pending.size.toDouble)) ++ accumulated
      id -> data
    buckets = buckets.updated(
      key,
      Bucket(
        previous.arrivals + arrivals,
        Option.when(statsFresh)(publication.stats.viewers.value.toDouble),
        Option.when(statsFresh)(publication.stats.chatRate.value.toDouble),
        samples.toMap
      )
    )
    val start = buckets.keys.minOption.getOrElse(key)
    val startAt = ApiTime.format(value.capturedAt.minusMillis(now - start * 5000L))
    def series(sample: Bucket => Option[Double]): ChartSeries_OUT =
      ChartSeries_OUT(startAt, 5000, (start to key).toVector.map(bucket => buckets.get(bucket).flatMap(sample)))
    Charts_OUT(
      generation,
      series(row => Some(row.arrivals)),
      series(_.viewers),
      series(_.chat),
      selected.map: device =>
        val id = device.link.device.value
        def metric(index: Int): ChartSeries_OUT = series(_.devices.get(id).flatMap(_(index)))
        DeviceCharts_OUT(id, metric(0), metric(1), metric(2), metric(3))
    )
