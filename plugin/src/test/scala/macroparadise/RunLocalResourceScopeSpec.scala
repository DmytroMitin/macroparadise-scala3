package macroparadise

final class RunLocalResourceScopeSpec extends munit.FunSuite:
  private final class TrackingResource(closeFailure: Option[String] = None)
      extends AutoCloseable:
    var closeCount = 0

    override def close(): Unit =
      closeCount += 1
      closeFailure.foreach(message => throw IllegalStateException(message))

  test("closes a run-local resource exactly once after successful use"):
    val resource = TrackingResource()

    val result = RunLocalResourceScope.use(resource)(_ => "completed")

    assertEquals(result, "completed")
    assertEquals(resource.closeCount, 1)

  test("closes a run-local resource exactly once when phase execution fails"):
    val resource = TrackingResource()

    val failure = intercept[IllegalStateException]:
      RunLocalResourceScope.use(resource): _ =>
        throw IllegalStateException("phase failed")

    assertEquals(failure.getMessage, "phase failed")
    assertEquals(resource.closeCount, 1)

  test("attempts every owned close and then reports the first close failure"):
    val failing = TrackingResource(Some("first close failed"))
    val following = TrackingResource()

    val failure = intercept[IllegalStateException]:
      RunLocalResourceScope.closeAll(List(failing, following))

    assertEquals(failure.getMessage, "first close failed")
    assertEquals(failing.closeCount, 1)
    assertEquals(following.closeCount, 1)
