package macroparadise

private[macroparadise] object RunLocalResourceScope:
  def use[A <: AutoCloseable, B](resource: A)(body: A => B): B =
    var primaryFailure: Throwable | Null = null
    try body(resource)
    catch
      case failure: Throwable =>
        primaryFailure = failure
        throw failure
    finally
      try resource.close()
      catch
        case closeFailure: Throwable if primaryFailure != null =>
          primaryFailure.addSuppressed(closeFailure)

  def closeAll(resources: Iterable[AutoCloseable]): Unit =
    var firstFailure: Throwable | Null = null
    resources.foreach: resource =>
      try resource.close()
      catch
        case failure: Throwable if firstFailure == null =>
          firstFailure = failure
        case failure: Throwable =>
          firstFailure.addSuppressed(failure)
    if firstFailure != null then throw firstFailure
