package macroparadise.sbt

import java.nio.file.Files
import munit.FunSuite

class OrdinaryWaitSourceSpec extends FunSuite {
  test("ordinary source adds one wire without entering the producer manifest") {
    val root = Files.createTempDirectory("ordinary-build").toRealPath()
    val a = Files.write(root.resolve("A.scala"), Array[Byte](1))
    val b = Files.write(root.resolve("B.scala"), Array[Byte](2))
    val main = Files.write(root.resolve("Main.scala"), Array[Byte](3))
    try {
      val config = SameModuleConfiguration.deriveSelected(root.toFile, root.resolve("out").toFile,
        None, Seq.empty, None, Seq(SameModuleEmbeddedBinding("demo.A", LabelledSource("a", "A.scala")),
        SameModuleEmbeddedBinding("demo.B", LabelledSource("b", "B.scala")))).right.get
      val files = Seq(a.toFile, b.toFile, main.toFile)
      assertEquals(OrdinaryWaitSource.options(root.toFile, files, config, None), Right(Vector.empty))
      val wire = OrdinaryWaitSource.options(root.toFile, files, config, Some("Main.scala")).right.get
      assertEquals(wire.size, 1)
      assert(wire.head.startsWith("-P:macroparadise:sameModuleOrdinaryWait=v1:"))
      assertEquals(config.sourceIdentity.sources.size, 2)
      Seq("", "../Main.scala", "Missing.scala", "A.scala", "B.scala").foreach { path =>
        assert(OrdinaryWaitSource.options(root.toFile, files, config, Some(path)).isLeft, path)
      }
      assert(OrdinaryWaitSource.options(root.toFile, files :+ main.toFile, config, Some("Main.scala")).isLeft)
      val singleton = SameModuleConfiguration.deriveEmbedded(root.toFile, root.resolve("out").toFile,
        SameModuleEmbeddedBinding("demo.A", LabelledSource("a", "A.scala")))
      assert(OrdinaryWaitSource.options(root.toFile, files, singleton, Some("Main.scala")).isLeft)
    } finally {
      Files.delete(main); Files.delete(a); Files.delete(b); Files.delete(root)
    }
  }
}
