package macroparadise

import java.nio.file.Path

class ActiveCompilerInspectorIdentitySpec extends munit.FunSuite:
  private val pinnedVersion =
    "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
  private val newerVersion =
    "3.10.0-RC1-bin-20260729-8526f78-NIGHTLY"
  private val compilerEntry =
    "dotty/tools/dotc/core/Contexts$Context.class"
  private val inspectorEntry =
    "scala/tasty/inspector/Inspector.class"

  private def compiler(
      version: String,
      fileName: Option[String] = None,
      manifestVersion: Option[String] = None,
      readableJar: Boolean = true,
      codeSource: Option[Path] = None,
      entries: Set[String] = Set(compilerEntry)
  ): LoadedArtifactFacts =
    LoadedArtifactFacts(
      role = "active compiler",
      codeSource = codeSource.orElse(
        Some(Path.of(fileName.getOrElse(s"scala3-compiler_3-$version.jar")))
      ),
      isReadableJar = readableJar,
      implementationVersion = manifestVersion.orElse(Some(version)),
      entries = entries
    )

  private def inspector(
      version: String,
      fileName: Option[String] = None,
      manifestVersion: Option[String] = None,
      readableJar: Boolean = true,
      codeSource: Option[Path] = None,
      entries: Set[String] = Set(
        "scala/",
        "scala/tasty/",
        "scala/tasty/inspector/",
        inspectorEntry
      )
  ): LoadedArtifactFacts =
    LoadedArtifactFacts(
      role = "active inspector",
      codeSource = codeSource.orElse(
        Some(
          Path.of(
            fileName.getOrElse(s"scala3-tasty-inspector_3-$version.jar")
          )
        )
      ),
      isReadableJar = readableJar,
      implementationVersion = manifestVersion.orElse(Some(version)),
      entries = entries
    )

  private def validate(
      compilerFacts: LoadedArtifactFacts,
      inspectorFacts: LoadedArtifactFacts
  ): Either[String, List[String]] =
    StructuredMetadataDistributionContract.validateActiveArtifactPair(
      compilerFacts,
      inspectorFacts
    )

  test("accepts the pinned exact active compiler and inspector pair") {
    val result = validate(compiler(pinnedVersion), inspector(pinnedVersion))
    assert(result.isRight, result)
    assert(result.toOption.get.forall(_.contains(s"version=$pinnedVersion")))
  }

  test("accepts the recorded newer exact compiler and inspector pair") {
    val result = validate(compiler(newerVersion), inspector(newerVersion))
    assert(result.isRight, result)
    assert(result.toOption.get.forall(_.contains(s"version=$newerVersion")))
  }

  test("rejects a compiler filename mismatch") {
    val result =
      validate(
        compiler(pinnedVersion, fileName = Some("scala3-compiler_3-wrong.jar")),
        inspector(pinnedVersion)
      )
    assert(result.left.toOption.get.contains("compiler filename mismatch"))
  }

  test("rejects a compiler missing its implementation version") {
    val facts =
      compiler(pinnedVersion).copy(implementationVersion = None)
    val result = validate(facts, inspector(pinnedVersion))
    assert(result.left.toOption.get.contains("compiler jar has no"))
  }

  test("rejects an inspector filename mismatch") {
    val result =
      validate(
        compiler(pinnedVersion),
        inspector(
          pinnedVersion,
          fileName = Some("scala3-tasty-inspector_3-wrong.jar")
        )
      )
    assert(result.left.toOption.get.contains("inspector filename mismatch"))
  }

  test("rejects an inspector missing its implementation version") {
    val facts =
      inspector(pinnedVersion).copy(implementationVersion = None)
    val result = validate(compiler(pinnedVersion), facts)
    assert(result.left.toOption.get.contains("inspector jar has no"))
  }

  test("rejects compiler and inspector manifest version mismatch") {
    val result =
      validate(
        compiler(pinnedVersion),
        inspector(
          pinnedVersion,
          manifestVersion = Some(newerVersion)
        )
      )
    assert(result.left.toOption.get.contains("manifest version mismatch"))
  }

  test("rejects an inspector missing the required Inspector class") {
    val result =
      validate(
        compiler(pinnedVersion),
        inspector(pinnedVersion, entries = Set("scala/tasty/inspector/"))
      )
    assert(result.left.toOption.get.contains("is missing"))
    assert(result.left.toOption.get.contains(inspectorEntry))
  }

  test("rejects a forbidden duplicate identity in the inspector") {
    val result =
      validate(
        compiler(pinnedVersion),
        inspector(
          pinnedVersion,
          entries = Set(inspectorEntry, "scala/quoted/Quotes.class")
        )
      )
    assert(result.left.toOption.get.contains("duplicates"))
    assert(result.left.toOption.get.contains("scala/quoted/Quotes.class"))
  }

  test("rejects an absent packaged compiler code source") {
    val facts =
      compiler(pinnedVersion).copy(codeSource = None)
    val result = validate(facts, inspector(pinnedVersion))
    assert(result.left.toOption.get.contains("compiler class has no code source"))
  }

  test("rejects a non-JAR packaged inspector code source") {
    val facts =
      inspector(pinnedVersion).copy(
        codeSource = Some(Path.of("target/classes")),
        isReadableJar = false
      )
    val result = validate(compiler(pinnedVersion), facts)
    assert(result.left.toOption.get.contains("must be a readable JAR"))
  }
