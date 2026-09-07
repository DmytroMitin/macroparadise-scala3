package macroparadise

import dotty.tools.dotc.Main
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.Trait
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}
import paradise3.api.*

import java.io.File
import java.nio.file.Files
import scala.collection.mutable

object RoleAwareNegativeCounters:
  private val constructions = mutable.Map.empty[String, Int].withDefaultValue(0)
  private val invocations = mutable.Map.empty[String, Int].withDefaultValue(0)
  def reset(): Unit =
    constructions.clear()
    invocations.clear()
  def constructed(id: String): Unit = constructions(id) += 1
  def invoked(id: String): Unit = invocations(id) += 1
  def constructionCount(id: String): Int = constructions(id)
  def invocationCount(id: String): Int = invocations(id)

object RoleAwareNegativeTrees:
  def freshType(
      name: String,
      source: dotty.tools.dotc.util.SourceFile,
      asTrait: Boolean
  )(using Context): TypeDef =
    given dotty.tools.dotc.util.SourceFile = source
    val raw = TypeDef(
      typeName(name),
      Template(emptyConstructor, Nil, Nil, EmptyValDef, Nil)
    )
    if asTrait then raw.withMods(Modifiers(Trait)).asInstanceOf[TypeDef] else raw

  def renamed(primary: ModuleDef, name: String)(using Context): ModuleDef =
    cpy.ModuleDef(primary)(termName(name), primary.impl)

  def withFreshCurrentAnnotation(
      primary: ModuleDef,
      current: Tree
  )(using Context): ModuleDef =
    val fresh = current match
      case apply: Apply => Apply(apply.fun, apply.args)
      case other        => other.withSpan(other.span)
    val mods = Trees.mods(primary)
    primary.withMods(mods.withAnnotations(mods.annotations :+ fresh)).asInstanceOf[ModuleDef]

  def withoutLastAnnotation(primary: ModuleDef)(using Context): ModuleDef =
    val mods = Trees.mods(primary)
    primary.withMods(mods.withAnnotations(mods.annotations.dropRight(1))).asInstanceOf[ModuleDef]

abstract class RoleAwareNegativeHandler(val id: String, val annotationName: String)
    extends RoleAwareParadiseAnnotationExpander:
  RoleAwareNegativeCounters.constructed(id)
  protected def result(input: RoleAwareExpansionInput)(using Context): RoleAwareExpansionOutcome
  final def expand(input: RoleAwareExpansionInput)(using Context): RoleAwareExpansionOutcome =
    RoleAwareNegativeCounters.invoked(id)
    result(input)
  protected def objectPrimary(input: RoleAwareExpansionInput): ModuleDef =
    input.primary.asInstanceOf[ExpansionPrimaryRole.Object].tree

final class CapabilityMismatchHandler
    extends RoleAwareNegativeHandler("capability", "capabilityMarker"):
  override val oppositeCapability = RoleAwareOppositeCapability.LeaseOrCreateClass
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Expanded(RoleAwareExpansionOutput(input.primary, OppositeChange.Preserve))

final class PrimaryOnlyReplaceHandler
    extends RoleAwareNegativeHandler("primaryReplace", "primaryReplaceMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(input.primary, OppositeChange.Replace(null))
    )

final class PrimaryOnlyCreateHandler
    extends RoleAwareNegativeHandler("primaryCreate", "primaryCreateMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val primary = objectPrimary(input)
    val created = RoleAwareNegativeTrees.freshType(primary.name.toString, primary.source, false)
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(
        input.primary,
        OppositeChange.Create(ExpansionOppositeRole.Class(created), OppositePlacement.BeforePrimary)
      )
    )

final class WrongPrimaryNameHandler
    extends RoleAwareNegativeHandler("wrongPrimaryName", "wrongPrimaryNameMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val wrong = RoleAwareNegativeTrees.renamed(objectPrimary(input), "Other")
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(ExpansionPrimaryRole.Object(wrong), OppositeChange.Preserve)
    )

final class WrongPrimaryRoleHandler
    extends RoleAwareNegativeHandler("wrongPrimaryRole", "wrongPrimaryRoleMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val primary = objectPrimary(input)
    val raw = RoleAwareNegativeTrees.freshType(primary.name.toString, primary.source, false)
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(ExpansionPrimaryRole.Class(raw), OppositeChange.Preserve)
    )

final class CounterfeitPrimaryHandler
    extends RoleAwareNegativeHandler("counterfeitPrimary", "counterfeitPrimaryMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val primary = objectPrimary(input)
    val rawTrait = RoleAwareNegativeTrees.freshType(primary.name.toString, primary.source, true)
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(ExpansionPrimaryRole.Class(rawTrait), OppositeChange.Preserve)
    )

final class CounterfeitOppositeHandler
    extends RoleAwareNegativeHandler("counterfeitOpposite", "counterfeitOppositeMarker"):
  override val oppositeCapability = RoleAwareOppositeCapability.LeaseOrCreateClassOrTrait
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val primary = objectPrimary(input)
    val rawTrait = RoleAwareNegativeTrees.freshType(primary.name.toString, primary.source, true)
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(
        input.primary,
        OppositeChange.Create(ExpansionOppositeRole.Class(rawTrait), OppositePlacement.BeforePrimary)
      )
    )

final class ReplaceWithoutLeaseHandler
    extends RoleAwareNegativeHandler("replaceNoLease", "replaceNoLeaseMarker"):
  override val oppositeCapability = RoleAwareOppositeCapability.LeaseExisting
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(input.primary, OppositeChange.Replace(null))
    )

final class CreateWithExistingHandler
    extends RoleAwareNegativeHandler("createExisting", "createExistingMarker"):
  override val oppositeCapability = RoleAwareOppositeCapability.LeaseOrCreateClassOrTrait
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(
        input.primary,
        OppositeChange.Create(input.leasedOpposite.get, OppositePlacement.AfterPrimary)
      )
    )

final class CreateWrongNameHandler
    extends RoleAwareNegativeHandler("createWrongName", "createWrongNameMarker"):
  override val oppositeCapability = RoleAwareOppositeCapability.LeaseOrCreateClass
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val primary = objectPrimary(input)
    val raw = RoleAwareNegativeTrees.freshType("Other", primary.source, false)
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(
        input.primary,
        OppositeChange.Create(ExpansionOppositeRole.Class(raw), OppositePlacement.BeforePrimary)
      )
    )

final class NullPlacementHandler
    extends RoleAwareNegativeHandler("nullPlacement", "nullPlacementMarker"):
  override val oppositeCapability = RoleAwareOppositeCapability.LeaseOrCreateClass
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val primary = objectPrimary(input)
    val raw = RoleAwareNegativeTrees.freshType(primary.name.toString, primary.source, false)
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(
        input.primary,
        OppositeChange.Create(ExpansionOppositeRole.Class(raw), null)
      )
    )

final class NullOutcomeHandler
    extends RoleAwareNegativeHandler("nullOutcome", "nullOutcomeMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) = null

final class EmptyRejectedHandler
    extends RoleAwareNegativeHandler("emptyRejected", "emptyRejectedMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Rejected(Nil)

final class ThrowingRoleAwareHandler
    extends RoleAwareNegativeHandler("throwing", "throwingMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    throw IllegalStateException("controlled role-aware fixture exception")

final class FirstParticipantHandler
    extends RoleAwareNegativeHandler("first", "firstMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Expanded(RoleAwareExpansionOutput(input.primary, OppositeChange.Preserve))

final class SecondParticipantHandler
    extends RoleAwareNegativeHandler("second", "secondMarker"):
  override val compositionPolicy = ExpansionCompositionPolicy.SourceOrdered
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Expanded(RoleAwareExpansionOutput(input.primary, OppositeChange.Preserve))

final class FreshHandledAnnotationHandler
    extends RoleAwareNegativeHandler("fresh", "freshMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val rewritten = RoleAwareNegativeTrees.withFreshCurrentAnnotation(
      objectPrimary(input),
      input.currentAnnotation
    )
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(ExpansionPrimaryRole.Object(rewritten), OppositeChange.Preserve)
    )

final class LateLineageFailureHandler
    extends RoleAwareNegativeHandler("late", "lateMarker"):
  protected def result(input: RoleAwareExpansionInput)(using Context) =
    val rewritten = RoleAwareNegativeTrees.withoutLastAnnotation(objectPrimary(input))
    RoleAwareExpansionOutcome.Expanded(
      RoleAwareExpansionOutput(ExpansionPrimaryRole.Object(rewritten), OppositeChange.Preserve)
    )

final class LegacyObjectHandler extends ParadiseAnnotationExpander:
  RoleAwareNegativeCounters.constructed("legacy")
  val annotationName = "legacyObjectMarker"
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    RoleAwareNegativeCounters.invoked("legacy")
    ExpansionOutcome.NotApplicable

final class BothInterfacesHandler
    extends ParadiseAnnotationExpander,
      RoleAwareParadiseAnnotationExpander:
  RoleAwareNegativeCounters.constructed("both")
  val annotationName = "bothMarker"
  override val compositionPolicy = ExpansionCompositionPolicy.StandaloneOnly
  def expand(input: ExpansionInput)(using Context) = ExpansionOutcome.NotApplicable
  def expand(input: RoleAwareExpansionInput)(using Context) =
    RoleAwareExpansionOutcome.Rejected(Nil)

final class NeitherInterfaceHandler:
  RoleAwareNegativeCounters.constructed("neither")

class RoleAwarePublicObjectCompilerSpec extends munit.FunSuite:
  private val scalaVersion = sys.props.getOrElse("macroparadise.testScalaVersion", "3.8.4")
  private val projectVersion = sys.props.getOrElse("macroparadise.testProjectVersion", "0.2.0-SNAPSHOT")
  private val pluginJar = new File(
    s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-$projectVersion.jar"
  ).getAbsolutePath
  private val pluginApiJar = new File(
    s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-$projectVersion.jar"
  ).getAbsolutePath

  private def codeSourcePath(clazz: Class[?]): String =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI).getAbsolutePath

  private val testClasses = codeSourcePath(classOf[NullOutcomeHandler])
  private val compileClasspath = Seq(
    codeSourcePath(classOf[scala.Option[?]]),
    codeSourcePath(classOf[scala.deriving.Mirror]),
    pluginJar,
    pluginApiJar
  ).distinct.mkString(File.pathSeparator)

  test("legacy object remains unsupported with zero legacy invocation") {
    RoleAwareNegativeCounters.reset()
    assertFailure(
      compile("legacyObjectMarker", classOf[LegacyObjectHandler]),
      "unsupported target"
    )
    assertEquals(RoleAwareNegativeCounters.invocationCount("legacy"), 0)
  }

  test("both and neither interfaces reject before construction") {
    RoleAwareNegativeCounters.reset()
    assertFailure(compile("bothMarker", classOf[BothInterfacesHandler]), "INVALID_HANDLER_INTERFACE_AMBIGUOUS_BOTH")
    assertEquals(RoleAwareNegativeCounters.constructionCount("both"), 0)
    assertFailure(compile("neitherMarker", classOf[NeitherInterfaceHandler]), "INVALID_HANDLER_INTERFACE_NEITHER")
    assertEquals(RoleAwareNegativeCounters.constructionCount("neither"), 0)
  }

  test("capability mismatch rejects before invocation") {
    RoleAwareNegativeCounters.reset()
    assertFailure(
      compile("capabilityMarker", classOf[CapabilityMismatchHandler], "trait Subject"),
      "OPPOSITE_CAPABILITY_MISMATCH"
    )
    assertEquals(RoleAwareNegativeCounters.invocationCount("capability"), 0)
  }

  List(
    ("primaryReplaceMarker", classOf[PrimaryOnlyReplaceHandler], "class Subject", "REPLACE_WITHOUT_LEASE"),
    ("primaryCreateMarker", classOf[PrimaryOnlyCreateHandler], "", "OPPOSITE_CAPABILITY_MISMATCH"),
    ("wrongPrimaryNameMarker", classOf[WrongPrimaryNameHandler], "", "PrimaryNameMismatch"),
    ("wrongPrimaryRoleMarker", classOf[WrongPrimaryRoleHandler], "", "PRIMARY_ROLE_MISMATCH"),
    ("counterfeitPrimaryMarker", classOf[CounterfeitPrimaryHandler], "", "COUNTERFEIT_PRIMARY_ROLE"),
    ("counterfeitOppositeMarker", classOf[CounterfeitOppositeHandler], "", "COUNTERFEIT_OPPOSITE_ROLE"),
    ("replaceNoLeaseMarker", classOf[ReplaceWithoutLeaseHandler], "", "REPLACE_WITHOUT_LEASE"),
    ("createExistingMarker", classOf[CreateWithExistingHandler], "class Subject", "CREATE_WITH_EXISTING_OPPOSITE"),
    ("createWrongNameMarker", classOf[CreateWrongNameHandler], "", "OppositeNameMismatch"),
    ("nullPlacementMarker", classOf[NullPlacementHandler], "", "INVALID_OPPOSITE_PLACEMENT"),
    ("nullOutcomeMarker", classOf[NullOutcomeHandler], "", "NULL_OUTCOME"),
    ("emptyRejectedMarker", classOf[EmptyRejectedHandler], "", "EMPTY_REJECTION_DIAGNOSTICS"),
    ("throwingMarker", classOf[ThrowingRoleAwareHandler], "", "NONFATAL_EXCEPTION"),
    ("freshMarker", classOf[FreshHandledAnnotationHandler], "", "FRESH_HANDLED_ANNOTATION")
  ).foreach: (marker, handler, opposite, category) =>
    test(s"$marker rejects transactionally as $category") {
      RoleAwareNegativeCounters.reset()
      assertFailure(compile(marker, handler, opposite), category)
    }

  test("two role-aware participants reject before both invocations") {
    RoleAwareNegativeCounters.reset()
    val source =
      """package roleawarenegative
        |import scala.annotation.StaticAnnotation
        |final class firstMarker extends StaticAnnotation
        |final class secondMarker extends StaticAnnotation
        |@firstMarker @secondMarker object Subject
        |""".stripMargin
    assertFailure(
      compileSource(source, List(classOf[FirstParticipantHandler], classOf[SecondParticipantHandler])),
      "MULTIPLE_ROLE_AWARE_PARTICIPANTS"
    )
    assertEquals(RoleAwareNegativeCounters.invocationCount("first"), 0)
    assertEquals(RoleAwareNegativeCounters.invocationCount("second"), 0)
  }

  test("a single SourceOrdered role-aware participant remains admitted") {
    RoleAwareNegativeCounters.reset()
    val outcome = compile("secondMarker", classOf[SecondParticipantHandler])
    outcome.threw.foreach(error => fail(s"uncontrolled failure ${error.getMessage}"))
    assertEquals(outcome.messages, Nil)
    assert(outcome.outputFiles.exists(_.endsWith("Subject$.class")), outcome.outputFiles)
    assertEquals(RoleAwareNegativeCounters.invocationCount("second"), 1)
  }

  test("matching legacy and role-aware handlers on one object fail before invocation") {
    RoleAwareNegativeCounters.reset()
    val source =
      """package roleawarenegative
        |import scala.annotation.StaticAnnotation
        |final class firstMarker extends StaticAnnotation
        |final class legacyObjectMarker extends StaticAnnotation
        |@firstMarker @legacyObjectMarker object Subject
        |""".stripMargin
    assertFailure(
      compileSource(source, List(classOf[FirstParticipantHandler], classOf[LegacyObjectHandler])),
      "AMBIGUOUS_LEGACY_ROLE_AWARE_PARTICIPANTS"
    )
    assertEquals(RoleAwareNegativeCounters.invocationCount("first"), 0)
    assertEquals(RoleAwareNegativeCounters.invocationCount("legacy"), 0)
  }

  test("later original annotation deletion rejects with exact rollback") {
    RoleAwareNegativeCounters.reset()
    val source =
      """package roleawarenegative
        |import scala.annotation.StaticAnnotation
        |final class lateMarker extends StaticAnnotation
        |final class untouched extends StaticAnnotation
        |@lateMarker @untouched object Subject
        |""".stripMargin
    assertFailure(compileSource(source, List(classOf[LateLineageFailureHandler])), "ANNOTATION_LINEAGE_MISMATCH")
  }

  private final class CollectingReporter extends SimpleReporter:
    val messages = mutable.ListBuffer.empty[String]
    override def report(diagnostic: Diagnostic): Unit = messages += diagnostic.message()

  private final case class CompileOutcome(messages: List[String], outputFiles: List[String], threw: Option[Throwable])

  private def compile(marker: String, handler: Class[?], opposite: String = ""): CompileOutcome =
    val source =
      s"""package roleawarenegative
         |import scala.annotation.StaticAnnotation
         |final class $marker extends StaticAnnotation
         |$opposite
         |@$marker object Subject
         |""".stripMargin
    compileSource(source, List(handler))

  private def compileSource(source: String, handlers: List[Class[?]]): CompileOutcome =
    val tempDir = Files.createTempDirectory("macroparadise-role-aware-negative")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, source)
    val reporter = CollectingReporter()
    val options = Array(
      "-classpath", compileClasspath,
      "-d", outDir.toString,
      s"-Xplugin:$pluginJar",
      "-Xplugin-require:macroparadise",
      s"-P:macroparadise:handlerClasspath=$testClasses"
    ) ++ handlers.map(value => s"-P:macroparadise:handler=${value.getName}") ++ Array(sourceFile.toString)
    try
      Main.process(options, reporter, null)
      CompileOutcome(reporter.messages.toList, regularFiles(outDir), None)
    catch
      case error: Throwable => CompileOutcome(reporter.messages.toList, regularFiles(outDir), Some(error))

  private def assertFailure(outcome: CompileOutcome, fragment: String): Unit =
    outcome.threw.foreach(error => fail(s"uncontrolled failure ${error.getClass.getName}: ${error.getMessage}"))
    val diagnostic = outcome.messages.mkString("\n")
    assert(diagnostic.contains(fragment), diagnostic)
    assertEquals(outcome.outputFiles, Nil, s"unexpected partial outputs: ${outcome.outputFiles.mkString(",")}")

  private def regularFiles(directory: java.nio.file.Path): List[String] =
    val paths = Files.walk(directory)
    try paths.filter(Files.isRegularFile(_)).map(path => directory.relativize(path).toString).sorted().toArray.toList.map(_.toString)
    finally paths.close()
