import paradise3.legacyExternalDebug

@legacyExternalDebug
final class LegacyMetadataMatrixUser

object LegacyMetadataMatrixConsumer:
  def result: String =
    new LegacyMetadataMatrixUser().legacyExternalDebugName

  def main(args: Array[String]): Unit =
    val actual = result
    require(
      actual == "LegacyMetadataMatrixUser",
      s"unexpected legacy metadata matrix result: $actual"
    )
    println(s"legacy metadata matrix consumer: $actual")
