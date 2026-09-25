package twitchscreen.relay.twitch

import java.time.{Duration, Instant}
import ox.{fork, supervised}

class WebhookDeduplicationSuite extends munit.FunSuite:
  private val now = Instant.ofEpochSecond(1000)

  test("concurrent claims publish one delivery"):
    supervised:
      val ids = WebhookDeduplication()
      val claims = (1 to 32).map(_ => fork(ids.claim("same", now))).map(_.join())
      assertEquals(claims.count(_ == Right(true)), 1)
      assertEquals(claims.count(_ == Right(false)), 31)

  test("capacity rejects new deliveries without forgetting live IDs, then expires"):
    val ids = WebhookDeduplication(1, Duration.ofSeconds(10))
    assertEquals(ids.claim("first", now), Right(true))
    assert(ids.claim("second", now).isLeft)
    assertEquals(ids.claim("first", now), Right(false))
    assertEquals(ids.claim("second", now.plusSeconds(10)), Right(true))
