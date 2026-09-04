package macroparadise

import dotty.tools.dotc.Main
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class PrivateObjectPrimaryCompilerSpec extends munit.FunSuite:
  import PrivateObjectPrimaryTransform.*
  import RoleAwareTransactionKernel.*

  private val scalaVersion =
    sys.props.getOrElse("macroparadise.testScalaVersion", "3.8.4")
  private val projectVersion =
    sys.props.getOrElse("macroparadise.testProjectVersion", "0.1.1-SNAPSHOT")
  private val pluginJar =
    new File(
      s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-$projectVersion.jar"
    ).getAbsolutePath
  private val pluginApiJar =
    new File(
      s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-$projectVersion.jar"
    ).getAbsolutePath
  private val markerJar =
    new File(
      s"plugin-test-markers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-markers_3-$projectVersion.jar"
    ).getAbsolutePath
  private val pluginPath = Seq(pluginJar, markerJar).mkString(File.pathSeparator)

  private def codeSourcePath(clazz: Class[?]): String =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI)
      .getAbsolutePath

  private val compileClasspath =
    Seq(
      codeSourcePath(classOf[scala.Option[?]]),
      codeSourcePath(classOf[scala.deriving.Mirror]),
      pluginJar,
      pluginApiJar,
      markerJar
    ).distinct.mkString(File.pathSeparator)

  test("private no-op participant compiles an object-primary transaction without a public handler") {
    val participant = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        Right(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(input.primary),
            input.opposite
          )
        )

    val outcome =
      withParticipant(participant): pluginOption =>
        compileSnippet(
          """package privateobject
            |
            |import scala.annotation.StaticAnnotation
            |
            |final class privateObjectFixture extends StaticAnnotation
            |
            |@privateObjectFixture
            |object NoOpposite:
            |  val preserved: Int = 1
            |""".stripMargin,
          pluginOption
        )

    outcome match
      case CompileOutcome.Succeeded(outputFiles) =>
        assert(outputFiles.contains("privateobject/NoOpposite$.class"), outputFiles)
        assert(outputFiles.contains("privateobject/NoOpposite.tasty"), outputFiles)
      case other => fail(s"expected private object transaction success, got $other")
  }

  test("private participant commits an object-primary member edit while preserving source annotations") {
    val participant = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        Right(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(
              appendStringMethod(input.primary, "foo", "object-primary")
            ),
            input.opposite
          )
        )

    val outcome =
      withParticipant(participant): pluginOption =>
        compileSnippet(
          """package privateobject
            |
            |import scala.annotation.StaticAnnotation
            |
            |final class privateObjectFixture extends StaticAnnotation
            |final class keep extends StaticAnnotation
            |
            |@privateObjectFixture
            |@keep
            |object EditedPrimary:
            |  val preserved: Int = 1
            |
            |object EditedPrimaryWitness:
            |  val value: String = EditedPrimary.foo
            |""".stripMargin,
          pluginOption
        )

    assertSucceeded(
      outcome,
      "privateobject/EditedPrimary$.class",
      "privateobject/EditedPrimaryWitness$.class"
    )
  }

  test("private participant commits bounded edits to existing class and trait opposites") {
    val participant = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        val nextOpposite = input.opposite.map:
          case OppositeRole.ClassOpposite(value) =>
            OppositeRole.ClassOpposite(
              appendStringMethod(value, "foo", "class-opposite")
            )
          case OppositeRole.TraitOpposite(value) =>
            OppositeRole.TraitOpposite(
              appendStringMethod(value, "foo", "trait-opposite")
            )
          case value => value
        Right(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(input.primary),
            nextOpposite
          )
        )

    val outcome =
      withParticipant(participant): pluginOption =>
        compileSnippet(
          """package privateobject
            |
            |import scala.annotation.StaticAnnotation
            |
            |final class privateObjectFixture extends StaticAnnotation
            |
            |class ExistingClass
            |@privateObjectFixture
            |object ExistingClass
            |
            |trait ExistingTrait
            |@privateObjectFixture
            |object ExistingTrait
            |
            |object OppositeWitness:
            |  val fromClass: String = new ExistingClass().foo
            |  val fromTrait: String = new ExistingTrait {}.foo
            |""".stripMargin,
          pluginOption
        )

    assertSucceeded(
      outcome,
      "privateobject/ExistingClass.class",
      "privateobject/ExistingTrait.class",
      "privateobject/OppositeWitness$.class"
    )
  }

  test("private participant no-op preserves existing class and trait opposite compilation") {
    val participant = noOpParticipant()
    val outcome =
      withParticipant(participant): pluginOption =>
        compileSnippet(
          """package privateobject
            |
            |import scala.annotation.StaticAnnotation
            |
            |final class privateObjectFixture extends StaticAnnotation
            |
            |class NoOpClass
            |@privateObjectFixture object NoOpClass
            |trait NoOpTrait
            |@privateObjectFixture object NoOpTrait
            |""".stripMargin,
          pluginOption
        )

    assertSucceeded(
      outcome,
      "privateobject/NoOpClass.class",
      "privateobject/NoOpTrait.class"
    )
  }

  test("ambiguous class and trait opposite topology rejects before private participant invocation") {
    val invocations = AtomicInteger(0)
    val participant = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        invocations.incrementAndGet()
        noOpResult(input)

    val outcome =
      withParticipant(participant): pluginOption =>
        compileSnippet(
          """package privateobject
            |
            |import scala.annotation.StaticAnnotation
            |final class privateObjectFixture extends StaticAnnotation
            |
            |class Ambiguous
            |trait Ambiguous
            |@privateObjectFixture object Ambiguous
            |""".stripMargin,
          pluginOption
        )

    assertEquals(invocations.get(), 0)
    assertPrivateFailure(
      outcome,
      "stage=discovery",
      "category=AmbiguousOppositeTopology"
    )
  }

  test("wrong primary name and primary or opposite kind replacement reject before commit") {
    val wrongName = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        Right(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(
              cpy.ModuleDef(input.primary)(termName("WrongName"), input.primary.impl)
            ),
            input.opposite
          )
        )

    val wrongNameOutcome =
      compileWithClassOpposite(wrongName, "WrongNameCase")
    assertPrivateFailure(
      wrongNameOutcome,
      "stage=role-validation",
      "category=PrimaryNameMismatch"
    )

    val wrongPrimaryKind = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        val existingClass = input.opposite.collect:
          case OppositeRole.ClassOpposite(value) => value
        .getOrElse(fail("missing class opposite"))
        Right(
          RoleAwareExpansionResult(
            PrimaryRole.ClassPrimary(existingClass),
            input.opposite
          )
        )

    val wrongPrimaryKindOutcome =
      compileWithClassOpposite(wrongPrimaryKind, "WrongPrimaryKindCase")
    assertPrivateFailure(
      wrongPrimaryKindOutcome,
      "stage=role-validation",
      "category=PrimaryKindMismatch"
    )

    val wrongOppositeKind = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        val existingClass = input.opposite.collect:
          case OppositeRole.ClassOpposite(value) => value
        .getOrElse(fail("missing class opposite"))
        Right(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(input.primary),
            Some(OppositeRole.TraitOpposite(existingClass))
          )
        )

    val wrongOppositeKindOutcome =
      compileWithClassOpposite(wrongOppositeKind, "WrongOppositeKindCase")
    assertPrivateFailure(
      wrongOppositeKindOutcome,
      "stage=role-validation",
      "category=OppositeKindMismatch"
    )
  }

  test("late private failure and controlled exception roll back with zero class or Tasty output") {
    val lateFailure = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        Right(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(
              appendStringMethod(input.primary, "foo", "must-rollback")
            ),
            input.opposite
          )
        )

      override def validateStaged(
          input: Input,
          result: RoleAwareExpansionResult
      )(using Context): Either[Failure, Unit] =
        Left(Failure("LATE_FIXTURE_FAILURE", "late private validation failed"))

    val lateOutcome = compileWithoutOpposite(lateFailure, "LateFailure")
    assertPrivateFailure(
      lateOutcome,
      "stage=late-validation",
      "category=LATE_FIXTURE_FAILURE"
    )

    val throwing = new Participant:
      val annotationName = "privateObjectFixture"

      def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
        throw IllegalStateException("controlled private fixture exception")

    val exceptionOutcome = compileWithoutOpposite(throwing, "ExceptionFailure")
    assertPrivateFailure(
      exceptionOutcome,
      "stage=transform",
      "category=NONFATAL_EXCEPTION",
      "controlled private fixture exception"
    )
  }

  private final class CollectingReporter extends SimpleReporter:
    val messages = scala.collection.mutable.ListBuffer.empty[String]

    override def report(diagnostic: Diagnostic): Unit =
      messages += diagnostic.message()

  private enum CompileOutcome:
    case ReportedErrors(messages: List[String], outputFiles: List[String])
    case Threw(throwable: Throwable, outputFiles: List[String])
    case Succeeded(outputFiles: List[String])

  private def noOpParticipant(): Participant = new Participant:
    val annotationName = "privateObjectFixture"

    def transform(input: Input)(using Context): Either[Failure, RoleAwareExpansionResult] =
      noOpResult(input)

  private def noOpResult(
      input: Input
  ): Either[Failure, RoleAwareExpansionResult] =
    Right(
      RoleAwareExpansionResult(
        PrimaryRole.ObjectPrimary(input.primary),
        input.opposite
      )
    )

  private def appendStringMethod(
      value: ModuleDef,
      name: String,
      result: String
  )(using Context): ModuleDef =
    val rewritten = appendStringMethod(value.impl, value.source, name, result)
    cpy.ModuleDef(value)(value.name, rewritten)

  private def appendStringMethod(
      value: TypeDef,
      name: String,
      result: String
  )(using Context): TypeDef =
    value.rhs match
      case template: Template =>
        cpy.TypeDef(value)(
          value.name,
          appendStringMethod(template, value.source, name, result)
        )
      case other => fail(s"expected template TypeDef, got $other")

  private def appendStringMethod(
      template: Template,
      source: dotty.tools.dotc.util.SourceFile,
      name: String,
      result: String
  )(using Context): Template =
    given dotty.tools.dotc.util.SourceFile = source
    val method =
      DefDef(
        termName(name),
        Nil,
        Ident(typeName("String")),
        Literal(Constant(result))
      )
    cpy.Template(template)(
      template.constr,
      template.parentsOrDerived,
      template.derived,
      template.self,
      template.body :+ method
    )

  private def compileWithClassOpposite(
      participant: Participant,
      name: String
  ): CompileOutcome =
    withParticipant(participant): pluginOption =>
      compileSnippet(
        s"""package privateobject
           |import scala.annotation.StaticAnnotation
           |final class privateObjectFixture extends StaticAnnotation
           |class $name
           |@privateObjectFixture object $name
           |""".stripMargin,
        pluginOption
      )

  private def compileWithoutOpposite(
      participant: Participant,
      name: String
  ): CompileOutcome =
    withParticipant(participant): pluginOption =>
      compileSnippet(
        s"""package privateobject
           |import scala.annotation.StaticAnnotation
           |final class privateObjectFixture extends StaticAnnotation
           |@privateObjectFixture object $name
           |""".stripMargin,
        pluginOption
      )

  private def assertSucceeded(
      outcome: CompileOutcome,
      expectedFiles: String*
  ): Unit =
    outcome match
      case CompileOutcome.Succeeded(outputFiles) =>
        expectedFiles.foreach: expected =>
          assert(outputFiles.contains(expected), outputFiles)
      case other => fail(s"expected private object transaction success, got $other")

  private def assertPrivateFailure(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        assertEquals(
          outputFiles,
          Nil,
          s"unexpected partial class/Tasty output: ${outputFiles.mkString(", ")}"
        )
      case CompileOutcome.Threw(throwable, outputFiles) =>
        fail(
          s"expected controlled private diagnostic, got ${throwable.getClass.getName}: " +
            s"${throwable.getMessage}; outputs=${outputFiles.mkString(", ")}"
        )
      case other => fail(s"expected controlled private diagnostic, got $other")

  private def compileSnippet(source: String, pluginOption: String): CompileOutcome =
    val tempDir = Files.createTempDirectory("macroparadise-private-object")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, source)

    val reporter = new CollectingReporter
    try
      val result =
        Main.process(
          Array(
            "-classpath",
            compileClasspath,
            "-d",
            outDir.toString,
            s"-Xplugin:$pluginPath",
            "-Xplugin-require:macroparadise",
            pluginOption,
            sourceFile.toString
          ),
          reporter,
          null
        )
      val outputFiles = regularFiles(outDir)
      if result.hasErrors() then
        CompileOutcome.ReportedErrors(reporter.messages.toList, outputFiles)
      else CompileOutcome.Succeeded(outputFiles)
    catch
      case throwable: Throwable =>
        CompileOutcome.Threw(throwable, regularFiles(outDir))

  private def regularFiles(directory: java.nio.file.Path): List[String] =
    val paths = Files.walk(directory)
    try
      paths
        .filter(path => Files.isRegularFile(path))
        .map(path => directory.relativize(path).toString)
        .sorted()
        .toArray
        .toList
        .map(_.toString)
    finally paths.close()
