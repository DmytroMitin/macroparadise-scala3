package macroparadise

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

class OrdinaryWaitConfigurationSpec extends munit.FunSuite:
  private def encoded(s: String) = Base64.getUrlEncoder.withoutPadding.encodeToString(s.getBytes(UTF_8))

  test("omission is inert; duplicate and malformed records fail closed") {
    assertEquals(OrdinaryWaitConfiguration.parse(Nil), Right(None))
    assert(OrdinaryWaitConfiguration.parse(List("sameModuleOrdinaryWait=v2:x:y")).isLeft)
    assert(OrdinaryWaitConfiguration.parse(List.fill(2)("sameModuleOrdinaryWait=v1:x:y")).isLeft)
  }

  test("rooted existing source round trips and noncanonical wire is rejected") {
    val root = Files.createTempDirectory("ordinary-wait-spec").toRealPath()
    try
      val source = Files.writeString(root.resolve("Main.scala"), "object Main")
      val wire = "sameModuleOrdinaryWait=v1:" + encoded(root.toString) + ":" + encoded("Main.scala")
      assertEquals(OrdinaryWaitConfiguration.parse(List(wire)).map(_.map(_.source)), Right(Some(source)))
      assert(OrdinaryWaitConfiguration.parse(List(wire + "=")).isLeft)
      assert(OrdinaryWaitConfiguration.parse(List("sameModuleOrdinaryWait=v1:" + encoded(root.toString + "/.") + ":" + encoded("Main.scala"))).isLeft)
      assert(OrdinaryWaitConfiguration.parse(List("sameModuleOrdinaryWait=v1:" + encoded(root.toString) + ":" + encoded("../Main.scala"))).isLeft)
    finally
      Files.deleteIfExists(root.resolve("Main.scala"))
      Files.deleteIfExists(root)
  }
