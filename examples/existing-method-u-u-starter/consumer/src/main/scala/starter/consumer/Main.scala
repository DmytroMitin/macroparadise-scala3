package starter.consumer

import starter.marker.rewriteEcho

@rewriteEcho
final class Service:
  val untouchedBefore = 1
  def echo(value: AnyVal): AnyVal = value
  val untouchedAfter = 2

object Main:
  def main(args: Array[String]): Unit =
    val service = new Service
    val result: Int = service.echo(-7)
    assert(result == 7, result)
    assert(service.untouchedBefore == 1, service.untouchedBefore)
    assert(service.untouchedAfter == 2, service.untouchedAfter)
    println(
      s"PUBLIC_EXISTING_METHOD_U_U_STARTER_PASS echo=$result " +
        s"before=${service.untouchedBefore} after=${service.untouchedAfter}"
    )
