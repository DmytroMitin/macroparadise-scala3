package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers

class ExplicitImportAnnotationIdentityResolverSpec extends munit.FunSuite:
  test("canonicalizes a short annotation through one preceding explicit import") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |import a.b.identity
        |@identity class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)

    assertEquals(resolver.identityOf(annotations.head).map(_.value), Right("a.b.identity"))
  }

  test("keeps a directly qualified annotation unchanged") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |import other.place.identity
        |@a.b.identity class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)

    assertEquals(resolver.identityOf(annotations.head).map(_.value), Right("a.b.identity"))
  }

  test("fails closed when two preceding explicit imports provide the same short name") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |import z.last.identity
        |import a.first.identity
        |@identity class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)
    val diagnostic = resolver.identityOf(annotations.head).left.toOption.get

    assertEquals(
      diagnostic.message,
      "ambiguous explicit-import annotation identity for `@identity`; candidates: a.first.identity, z.last.identity; use a qualified annotation"
    )
    assertEquals(diagnostic.pos.span, annotations.head.sourcePos.span)
  }

  test("does not treat a wildcard import as an explicit identity witness") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |import a.b.*
        |@identity class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)

    assertEquals(resolver.identityOf(annotations.head).map(_.value), Right("identity"))
  }

  test("does not treat a renamed import as an explicit identity witness") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |import a.b.identity as renamed
        |@renamed class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)

    assertEquals(resolver.identityOf(annotations.head).map(_.value), Right("renamed"))
  }

  test("does not guess a package for a short annotation without an explicit import") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |@identity class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)

    assertEquals(resolver.identityOf(annotations.head).map(_.value), Right("identity"))
  }

  test("preserves the reserved paradise3 legacy simple-identity import lane") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |import paradise3.externalDebug
        |@externalDebug class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)

    assertEquals(resolver.identityOf(annotations.head).map(_.value), Right("externalDebug"))
  }

  test("can retain an imported short identity after selecting a legacy simple descriptor") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |import legacy.marker.identity
        |@identity class Something
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)
    val request = resolver.requestOf(annotations.head).toOption.get
    assertEquals(request.annotationName, "legacy.marker.identity")
    assertEquals(request.importedShortName, Some("identity"))

    resolver.preferLegacySimpleIdentity(request)

    assertEquals(resolver.identityOf(annotations.head).map(_.value), Right("identity"))
  }

  test("applies an explicit import only to following package statistics") {
    val (tree, annotations, context) = parsedAnnotations(
      """package consumer
        |@identity class Before
        |import a.b.identity
        |@identity class After
        |""".stripMargin
    )
    given Context = context

    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)

    assertEquals(
      annotations.map(annotation => resolver.identityOf(annotation).map(_.value)),
      List(Right("identity"), Right("a.b.identity"))
    )
  }

  test("fails closed when a nested import can shadow a package explicit import") {
    val unit = CompilationUnit(
      "ExplicitImportAnnotationIdentityResolverSpec.scala",
      """package consumer
        |import a.first.identity
        |object NestedScope:
        |  import z.local.identity
        |  @identity class Something
        |""".stripMargin
    )
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    given Context = context
    val parsed = new Parsers.Parser(unit.source).parse()
    val nestedAnnotation =
      parsed match
        case PackageDef(_, stats) =>
          stats.collectFirst:
            case moduleDef: ModuleDef =>
              moduleDef.impl.body.collectFirst:
                case typeDef: TypeDef if Trees.mods(typeDef).annotations.nonEmpty =>
                  Trees.mods(typeDef).annotations.head
          .flatten
          .getOrElse(fail("expected a nested annotated class"))
        case other => fail(s"expected package tree, found $other")

    val diagnostic =
      ExplicitImportAnnotationIdentityResolver
        .fromUnitTree(parsed)
        .identityOf(nestedAnnotation)
        .left
        .toOption
        .get

    assertEquals(
      diagnostic.message,
      "unsupported local/nested import scope for short annotation `@identity`; use a qualified annotation"
    )
    assertEquals(diagnostic.pos.span, nestedAnnotation.sourcePos.span)
  }

  private def parsedAnnotations(code: String): (Tree, List[Tree], Context) =
    val unit = CompilationUnit("ExplicitImportAnnotationIdentityResolverSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val annotations =
      parsed match
        case PackageDef(_, stats) =>
          stats.collect:
            case typeDef: TypeDef if Trees.mods(typeDef).annotations.nonEmpty =>
              Trees.mods(typeDef).annotations.head
        case other => fail(s"expected package tree, found $other")
    (parsed, annotations, context)
