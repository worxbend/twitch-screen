package twitchscreen.relay.twitch

import java.time.{Duration, Instant}
import ox.{fork, supervised}

class WebhookDeduplicationSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-09-25T12:00:00Z")

  test("only one concurrent delivery owns an id until completion"):
    supervised:
      val ids = WebhookDeduplication()
      val results = (1 to 32).map(_ => fork(ids.claim("same", now))).map(_.join())
      assertEquals(results.count(_ == Right(WebhookClaim.Fresh)), 1)
      assertEquals(results.count(_.isLeft), 31)
      ids.complete("same")
      assertEquals(ids.claim("same", now), Right(WebhookClaim.Duplicate))

  test("saturation preserves completed ids until expiry"):
    val ids = WebhookDeduplication(capacity = 1, lifetime = Duration.ofSeconds(10))
    assertEquals(ids.claim("first", now), Right(WebhookClaim.Fresh))
    ids.complete("first")
    assert(ids.claim("second", now).isLeft)
    assertEquals(ids.claim("first", now), Right(WebhookClaim.Duplicate))
    assertEquals(ids.claim("second", now.plusSeconds(10)), Right(WebhookClaim.Fresh))

  test("a failed dispatch releases its claim for redelivery"):
    val ids = WebhookDeduplication()
    assertEquals(ids.claim("retry", now), Right(WebhookClaim.Fresh))
    ids.release("retry")
    assertEquals(ids.claim("retry", now), Right(WebhookClaim.Fresh))
