package twitchscreen.relay.device

/** K-147: the accept-loop log cadences are pure, so their behaviour past the old saturation point is pinned without sockets. */
class DeviceLinkServerLoggingSuite extends munit.FunSuite:
  test("failure log cadence never saturates"):
    List(1L, 10L, 30L, 40L, 1000L).foreach(n => assert(DeviceLinkServer.shouldLogFailure(n), s"failure $n should log"))
    List(2L, 9L, 11L, 31L, 999L).foreach(n => assert(!DeviceLinkServer.shouldLogFailure(n), s"failure $n should not log"))

  test("refusal log cadence is the first refusal and every 100th"):
    List(1L, 100L, 200L).foreach(n => assert(DeviceLinkServer.shouldLogRefusal(n), s"refusal $n should log"))
    List(2L, 50L, 99L, 101L, 199L).foreach(n => assert(!DeviceLinkServer.shouldLogRefusal(n), s"refusal $n should not log"))
