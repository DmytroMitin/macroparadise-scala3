package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{
  AnnotationApplication,
  AnnotationTermArgument,
  ExpansionInput
}

class AnnotationApplicationSpec extends munit.FunSuite:
  test("records the pinned raw shapes for simple and qualified annotation constructors") {
    val cases = List(
      ("@audit class Simple", "Apply(Select(New(Ident(audit)),<init>),0)", "audit"),
      ("@audit() class SimpleApply", "Apply(Select(New(Ident(audit)),<init>),0)", "audit"),
      ("@foo.audit class Qualified", "Apply(Select(New(Select(Ident(foo),audit)),<init>),0)", "foo.audit"),
      ("@foo.audit() class QualifiedApply", "Apply(Select(New(Select(Ident(foo),audit)),<init>),0)", "foo.audit"),
      ("@foo.bar.audit class DeepQualified", "Apply(Select(New(Select(Select(Ident(foo),bar),audit)),<init>),0)", "foo.bar.audit"),
      ("@foo.bar.audit() class DeepQualifiedApply", "Apply(Select(New(Select(Select(Ident(foo),bar),audit)),<init>),0)", "foo.bar.audit"),
      ("@foo.audit[String](\"x\") class QualifiedTyped", "Apply(Select(New(AppliedTypeTree(Select(Ident(foo),audit),1)),<init>),1)", "foo.audit")
    )

    cases.foreach: (source, expectedShape, expectedIdentity) =>
      val (input, context) = parsedInput(source)
      given Context = context
      val annotation = input.currentAnnotation.get
      val actualShape = rawShape(annotation)
      val actualIdentity = SyntacticAnnotationIdentity.fromTree(annotation).map(_.value)
      println(s"QUALIFIED_ANNOTATION_PARSER_SHAPE source=${source.takeWhile(_ != ' ')} shape=$actualShape identity=${actualIdentity.getOrElse("<none>")}")
      assertEquals(actualShape, expectedShape)
      assertEquals(actualIdentity, Some(expectedIdentity))
  }

  test("normalizes one positional string argument") {
    val (input, context) =
      parsedInput("@externalTypedLabel[Int](\"alpha\") class Positional")
    given Context = context

    val application = normalized(input)
    assertEquals(application.annotationName, "externalTypedLabel")
    assertEquals(application.typeArguments.size, 1)
    assertEquals(application.termArguments.size, 1)
    application.termArguments.head match
      case AnnotationTermArgument.Positional(Literal(constant), pos) =>
        assertEquals(constant.stringValue, "alpha")
        assert(pos.span.exists)
      case other =>
        fail(s"expected positional literal, found $other")
    assert(application.rawTree eq input.currentAnnotation.get)
    assert(application.pos.span.exists)
  }

  test("normalizes named value argument separately from positional syntax") {
    val (input, context) =
      parsedInput(
        "@externalTypedLabel[Int](value = \"beta\") class Named"
      )
    given Context = context

    val application = normalized(input)
    application.termArguments.head match
      case AnnotationTermArgument.Named(name, Literal(constant), pos) =>
        assertEquals(name, "value")
        assertEquals(constant.stringValue, "beta")
        assert(pos.span.exists)
      case other =>
        fail(s"expected named literal, found $other")
    assertEquals(
      application.requireSingleStringLiteralArgument("value"),
      Right("beta")
    )
  }

  test("retains the exact syntactic name from a qualified annotation") {
    val (input, context) =
      parsedInput(
        "@paradise3.externalTypedLabel[Int](\"qualified\") class Qualified"
      )
    given Context = context

    val application = normalized(
      input.copy(annotationName = "paradise3.externalTypedLabel")
    )
    assertEquals(application.annotationName, "paradise3.externalTypedLabel")
    assertEquals(
      application.requireSingleStringLiteralArgument("value"),
      Right("qualified")
    )
  }

  test("preserves one raw type argument") {
    val (input, context) =
      parsedInput("@externalTypedLabel[Int](\"value\") class OneType")
    given Context = context

    normalized(input).typeArguments match
      case Ident(name) :: Nil =>
        assertEquals(name.toString, "Int")
      case other =>
        fail(s"expected one raw Int type argument, found $other")
  }

  test("preserves multiple raw type arguments in source order before validation") {
    val (input, context) =
      parsedInput(
        "@externalTypedLabel[Int, String](\"value\") class MultipleTypes"
      )
    given Context = context

    val application = normalized(input)
    val names = application.typeArguments.collect:
      case Ident(name) => name.toString
    assertEquals(names, List("Int", "String"))
  }

  test("rejects missing current annotation") {
    val (input, context) =
      parsedInput("@externalTypedLabel[Int](\"value\") class MissingCurrent")
    given Context = context

    val diagnostic =
      AnnotationApplication.fromInput(input.copy(currentAnnotation = None)).left.toOption.get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel annotation application is unavailable: current raw annotation tree is missing"
    )
    assertEquals(diagnostic.pos.span, input.annotatedClass.sourcePos.span)
  }

  test("rejects annotation name mismatch") {
    val (input, context) =
      parsedInput("@externalTypedLabel[Int](\"value\") class Mismatch")
    given Context = context

    val diagnostic =
      AnnotationApplication
        .fromInput(input.copy(annotationName = "different"))
        .left
        .toOption
        .get
    assertEquals(
      diagnostic.message,
      "annotation application name mismatch: input expects @different but raw tree names @externalTypedLabel"
    )
    assertEquals(diagnostic.pos.span, input.currentAnnotation.get.sourcePos.span)
  }

  test("rejects unsupported outer raw shape") {
    val (input, context) =
      parsedInput("@externalTypedLabel[Int](\"value\") class Unsupported")
    given Context = context
    val unsupported = normalized(input).typeArguments.head

    val diagnostic =
      AnnotationApplication
        .fromInput(input.copy(currentAnnotation = Some(unsupported)))
        .left
        .toOption
        .get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel has unsupported raw annotation application shape `Ident`; expected a constructor application"
    )
    assertEquals(diagnostic.pos.span, unsupported.sourcePos.span)
  }

  test("rejects a wrong named argument at the complete named-argument position") {
    val (input, context) =
      parsedInput(
        "@externalTypedLabel[Int](other = \"x\") class WrongNamed"
      )
    given Context = context

    val application = normalized(input)
    val diagnostic =
      application.requireSingleStringLiteralArgument("value").left.toOption.get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel argument 0 uses unsupported named parameter `other`; expected `value`"
    )
    val namedArgumentPos =
      input.currentAnnotation.get match
        case Apply(_, argument :: Nil) => argument.sourcePos
        case other => fail(s"expected raw Apply with one argument, found $other")
    assertEquals(diagnostic.pos.span, namedArgumentPos.span)
  }

  test("rejects a non-literal value at the value position") {
    val (input, context) =
      parsedInput(
        "val dynamic = \"dynamic\"; @externalTypedLabel[Int](dynamic) class NonLiteral"
      )
    given Context = context

    val application = normalized(input)
    val diagnostic =
      application.requireSingleStringLiteralArgument("value").left.toOption.get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel parameter `value` (argument 0) requires a string literal; found raw Ident"
    )
    assertEquals(diagnostic.pos.span, application.termArguments.head.pos.span)
  }

  test("rejects a missing explicit type argument") {
    val (input, context) =
      parsedInput(
        "@externalTypedLabel(\"missing type argument\") class MissingType"
      )
    given Context = context

    val application = normalized(input)
    val diagnostic =
      application.requireExactlyOneTypeArgument.left.toOption.get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel requires exactly one explicit type argument; found 0"
    )
    assertEquals(diagnostic.pos.span, application.pos.span)
  }

  test("rejects extra type arguments at the first extra argument") {
    val (input, context) =
      parsedInput(
        "@externalTypedLabel[Int, String](\"value\") class ExtraType"
      )
    given Context = context

    val application = normalized(input)
    val diagnostic =
      application.requireExactlyOneTypeArgument.left.toOption.get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel requires exactly one explicit type argument; found 2"
    )
    assertEquals(
      diagnostic.pos.span,
      application.typeArguments(1).sourcePos.span
    )
  }

  test("rejects a missing term argument") {
    val (input, context) =
      parsedInput("@externalTypedLabel[Int]() class MissingTerm")
    given Context = context

    val application = normalized(input)
    val diagnostic =
      application.requireSingleStringLiteralArgument("value").left.toOption.get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel requires exactly one term argument for parameter `value`; found 0"
    )
    assertEquals(diagnostic.pos.span, application.pos.span)
  }

  test("rejects extra term arguments at the first extra argument") {
    val (input, context) =
      parsedInput(
        "@externalTypedLabel[Int](\"first\", \"second\") class ExtraTerm"
      )
    given Context = context

    val application = normalized(input)
    val diagnostic =
      application.requireSingleStringLiteralArgument("value").left.toOption.get
    assertEquals(
      diagnostic.message,
      "@externalTypedLabel requires exactly one term argument for parameter `value`; found 2"
    )
    assertEquals(diagnostic.pos.span, application.termArguments(1).pos.span)
  }

  test("returns the validated type and positional string value") {
    val (input, context) =
      parsedInput("@externalTypedLabel[Int](\"value\") class Validated")
    given Context = context

    val application = normalized(input)
    assert(application.requireExactlyOneTypeArgument.isRight)
    assertEquals(
      application.requireSingleStringLiteralArgument("value"),
      Right("value")
    )
  }

  private def normalized(
      input: ExpansionInput
  )(using Context): AnnotationApplication =
    AnnotationApplication.fromInput(input) match
      case Right(application) => application
      case Left(diagnostic) => fail(diagnostic.message)

  private def rawShape(tree: Tree): String =
    tree match
      case Apply(fn, arguments) =>
        s"Apply(${rawShape(fn)},${arguments.size})"
      case TypeApply(fn, arguments) =>
        s"TypeApply(${rawShape(fn)},${arguments.size})"
      case Select(qualifier, name) =>
        s"Select(${rawShape(qualifier)},${name.toString})"
      case New(tpt) =>
        s"New(${rawShape(tpt)})"
      case AppliedTypeTree(tpt, arguments) =>
        s"AppliedTypeTree(${rawShape(tpt)},${arguments.size})"
      case Ident(name) =>
        s"Ident(${name.toString})"
      case other =>
        other.getClass.getSimpleName

  private def parsedInput(
      code: String
  ): (ExpansionInput, Context) =
    val unit = CompilationUnit("AnnotationApplicationSpec.scala", code)
    val context =
      ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed =
      new Parsers.Parser(unit.source)(using context).parse()
    val annotatedClass =
      parsed match
        case PackageDef(_, stats) =>
          stats.collectFirst:
            case typeDef: TypeDef
                if Trees.mods(typeDef).annotations.nonEmpty =>
              typeDef
          .getOrElse(fail(s"no annotated class in parsed tree $parsed"))
        case typeDef: TypeDef
            if Trees.mods(typeDef).annotations.nonEmpty =>
          typeDef
        case other =>
          fail(s"no package or annotated class in parsed tree $other")
    val annotation =
      Trees.mods(annotatedClass).annotations.head

    (
      ExpansionInput(
        annotationName = "externalTypedLabel",
        annotatedClass = annotatedClass,
        existingCompanion = None,
        topLevelNames = Set(annotatedClass.name.toString),
        currentAnnotation = Some(annotation)
      ),
      context
    )
