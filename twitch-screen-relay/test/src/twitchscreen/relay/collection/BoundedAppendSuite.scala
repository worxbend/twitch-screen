package twitchscreen.relay.collection

class BoundedAppendSuite extends munit.FunSuite:
  test("K-096: capacity 1 keeps only the newest item"):
    assertEquals(Vector.empty[Int].appendBounded(1, 1), Vector(1))
    assertEquals(Vector(1).appendBounded(2, 1), Vector(2))

  test("K-096: below capacity nothing is dropped and order is kept"):
    assertEquals(Vector(1, 2).appendBounded(3, 5), Vector(1, 2, 3))

  test("K-096: at capacity the oldest item is dropped first"):
    assertEquals(Vector(1, 2, 3).appendBounded(4, 3), Vector(2, 3, 4))
    assertEquals((1 to 10).foldLeft(Vector.empty[Int])(_.appendBounded(_, 3)), Vector(8, 9, 10))

  test("K-096: over capacity, as after a shrink, trims down to capacity"):
    assertEquals(Vector(1, 2, 3, 4).appendBounded(5, 2), Vector(4, 5))
