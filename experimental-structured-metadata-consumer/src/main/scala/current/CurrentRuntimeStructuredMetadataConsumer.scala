import paradise3.externalDebug

@externalDebug
final class CurrentRuntimeStructuredMetadataUser

object CurrentRuntimeStructuredMetadataConsumer:
  def main(args: Array[String]): Unit =
    val actual =
      new CurrentRuntimeStructuredMetadataUser().externalDebugName
    require(
      actual == "CurrentRuntimeStructuredMetadataUser",
      s"unexpected current-marker runtime metadata result: $actual"
    )
    println(s"current-marker runtime metadata consumer: $actual")
