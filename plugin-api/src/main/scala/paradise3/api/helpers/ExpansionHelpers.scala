package paradise3.api.helpers

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.{
  AnnotatedClassView,
  ExpansionDiagnostic,
  ExpansionInput,
  ExpansionOutcome,
  StructuredExpansionOutput
}

/** Optional helpers for the experimental external-handler API.
  *
  * These helpers layer small conveniences over the raw `untpd` compiler-tree
  * surface exposed by `ExpansionInput`. They support the current D core plus E
  * helpers direction: low-level tree access remains available for power and
  * experimentation, while narrow common operations can be packaged as helpers.
  *
  * This object is intentionally incomplete. Its structured helper is one
  * experimental role wrapper, not a stable public macro-annotation API or a
  * replacement for raw compiler-tree access.
  */
object ExpansionHelpers:
  /** Build a successful expansion outcome from already-constructed raw trees. */
  def expanded(trees: List[untpd.Tree]): ExpansionOutcome =
    ExpansionOutcome.Expanded(trees)

  /** Build a role-structured successful output for the current common shapes. */
  def structured(
      primary: TypeDef,
      companion: Option[ModuleDef] = None,
      additionalTopLevelDefinitions: List[untpd.Tree] = Nil
  ): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(
        primary,
        companion,
        additionalTopLevelDefinitions
      )
    )

  /** Build a rejected outcome with one diagnostic and the fallback class tree. */
  def rejected(message: String, fallback: TypeDef)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(List(ExpansionDiagnostic(message, fallback.sourcePos)), fallback)

  /** Build a rejected outcome while preserving an existing decoder diagnostic. */
  def rejected(
      diagnostic: ExpansionDiagnostic,
      fallback: TypeDef
  ): ExpansionOutcome =
    ExpansionOutcome.Rejected(List(diagnostic), fallback)

  /** Decode the bounded annotated-class view for one controlled handler action.
    *
    * Successful callbacks own their complete outcome, including `null`,
    * `NotApplicable`, and deliberately malformed values retained for coordinator
    * tests. Callback failures are not caught here. A decoder failure becomes one
    * unchanged diagnostic plus the exact raw annotated class fallback. Hostile
    * direct callers that provide no input or no usable fallback fail with a
    * focused argument error rather than a fabricated tree.
    */
  def withAnnotatedClassView(
      input: ExpansionInput
  )(
      use: AnnotatedClassView => ExpansionOutcome
  )(using Context): ExpansionOutcome =
    if input == null then
      throw new IllegalArgumentException(
        "withAnnotatedClassView requires a non-null ExpansionInput"
      )
    else
      input.annotatedClassView match
        case Right(view) =>
          if use == null then
            throw new IllegalArgumentException(
              "withAnnotatedClassView requires a non-null callback after successful decoding"
            )
          use(view)
        case Left(diagnostic) =>
          if input.annotatedClass == null then
            throw new IllegalArgumentException(
              s"withAnnotatedClassView cannot form a rejected outcome without a fallback annotated class: ${diagnostic.message}"
            )
          rejected(diagnostic, input.annotatedClass)

  /** Construct a parameterless method returning a string literal.
    *
    * This helper still creates raw untyped compiler trees. It only packages the
    * current narrow boilerplate used by the precompiled handler fixtures.
    * Positions come from the active source file passed by the caller and remain
    * compiler-version-sensitive.
    */
  def stringReturningMethod(name: String, value: String, source: dotty.tools.dotc.util.SourceFile)(using Context): DefDef =
    // MAY DEPEND ON SCALA VERSION
    // This helper still constructs raw untpd trees; it only packages the current
    // narrow boilerplate for a parameterless String-returning method.
    given dotty.tools.dotc.util.SourceFile = source

    untpd.DefDef(
      termName(name),
      Nil,
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant(value))
    )

  /** Add a parameterless string-returning method to the annotated class.
    *
    * This is the current convenience path used by `ExternalDebugExpander`.
    * It supports only the narrow top-level class fixture shape and returns
    * `NotApplicable` for anything outside that shape.
    *
    * The rewrite preserves the class name, constructor, existing body members,
    * modifiers, parents/template shape, and the original class tree source
    * position through compiler copy helpers. It appends the generated method
    * after existing body members when the method name is absent. If the class
    * already defines `methodName`, the user-defined member wins and generation
    * is skipped. When `input.currentAnnotation` is supplied, only that exact
    * raw annotation tree is removed; later handled annotations and unhandled
    * annotations are preserved on the returned class. When
    * `input.currentAnnotation` is absent, the helper uses the legacy fallback
    * that clears the entire raw class annotation list.
    */
  def addStringMethodToClass(input: ExpansionInput, methodName: String, value: String)(using Context): ExpansionOutcome =
    input.annotatedClass.rhs match
      case template: Template =>
        val strippedClass = stripCurrentAnnotation(input)
        val existingBody = template.body(using summon[Context])
        val rewrittenBody =
          if classHasMethod(template, methodName) then existingBody
          else existingBody :+ stringReturningMethod(methodName, value, input.annotatedClass.source)

        // ASSUMPTION
        // This mirrors the existing external-handler template rewrite and keeps
        // support intentionally limited to the current top-level class fixtures.
        val rewrittenTemplate =
          untpd.cpy.Template(template)(
            template.constr,
            template.parentsOrDerived(using summon[Context]),
            template.derived,
            template.self,
            rewrittenBody
          )

        structured(
          untpd.cpy.TypeDef(strippedClass)(
            strippedClass.name,
            rewrittenTemplate
          )
        )
      case _ =>
        ExpansionOutcome.NotApplicable

  /** Add a parameterless string-returning method to a companion object.
    *
    * This helper supports the current top-level class fixture shape. It creates
    * a companion when none was passed in `input.existingCompanion`, or merges
    * into that companion when the handler opted in through
    * `consumesExistingCompanion`. If the companion already defines `methodName`,
    * the existing user definition wins and no duplicate is generated.
    *
    * The annotated class is returned with only the current handled annotation
    * removed when `input.currentAnnotation` is available. When merging, existing
    * companion members, constructor, parents/template shape, and source position
    * are preserved through compiler copy helpers; the generated method is
    * appended only when absent. Companion discovery is syntactic and limited to
    * the following top-level companion supplied by the plugin, not semantic
    * companion resolution.
    */
  def addStringMethodToCompanion(input: ExpansionInput, methodName: String, value: String)(using Context): ExpansionOutcome =
    val generatedMethod =
      stringReturningMethod(methodName, value, input.annotatedClass.source)
    addMethodToCompanion(
      input,
      generatedMethod,
      CompanionMethodConflictPolicy.PreserveExisting
    )

  /** Place an already-created raw method in the annotated class's companion.
    *
    * The caller owns construction and lowering of `generatedMethod`; this
    * helper inserts that exact `DefDef` without rebuilding or interpreting its
    * syntax. Placement is a syntactic pre-typer operation. When a same-name
    * companion is supplied, only direct raw `MemberDef` names participate in
    * conflict detection, existing member order is preserved, and a non-
    * conflicting method is appended last. Nested definitions and semantic
    * overload resolution are outside this contract.
    *
    * `PreserveExisting` returns successful structured output with the exact
    * existing companion unchanged. `Reject` returns one diagnostic and the
    * original annotated class fallback without consuming its current
    * annotation or returning a partial companion. Successful output removes
    * only `input.currentAnnotation` when present and otherwise retains the
    * established direct-caller fallback that clears all raw annotations.
    *
    * This compiler-version-sensitive experimental helper accepts only
    * `untpd.DefDef`; it is not arbitrary member placement or a general
    * annotation-authoring facade.
    */
  def addMethodToCompanion(
      input: ExpansionInput,
      generatedMethod: untpd.DefDef,
      conflictPolicy: CompanionMethodConflictPolicy
  )(using Context): ExpansionOutcome =
    placeMemberInCompanion(
      input,
      generatedMethod,
      companionHasDirectMemberNamed(_, generatedMethod.name),
      preserveExisting = conflictPolicy == CompanionMethodConflictPolicy.PreserveExisting,
      ExpansionDiagnostic(
        s"generated companion method `${generatedMethod.name}` conflicts with existing direct companion member `${generatedMethod.name}` for `${input.className}`",
        mostSpecificCurrentAnnotationPosition(input)
      )
    )

  /** Place an already-created raw type definition in the annotated class's companion.
    *
    * The caller owns construction and lowering of `generatedType`; this helper
    * inserts that exact `TypeDef` without rebuilding, parsing, re-lowering, or
    * interpreting its syntax. A direct conflict is deliberately limited to a
    * raw direct companion `TypeDef` with the same `TypeName`. This includes raw
    * type aliases/members and nested class or trait definitions, while direct
    * term-only definitions with the same decoded spelling remain outside the
    * type namespace. Nested/non-direct definitions and semantic name resolution
    * are outside this contract.
    *
    * `PreserveExisting` returns successful structured output with the exact
    * existing companion unchanged. `Reject` returns one type-specific diagnostic
    * and the original annotated class fallback without a partial companion.
    * Successful output removes only `input.currentAnnotation` when present and
    * otherwise retains the established direct-caller clear-all fallback.
    *
    * This compiler-version-sensitive experimental helper accepts only
    * `untpd.TypeDef`; it is not arbitrary `MemberDef` placement.
    */
  def addTypeToCompanion(
      input: ExpansionInput,
      generatedType: untpd.TypeDef,
      conflictPolicy: CompanionTypeConflictPolicy
  )(using Context): ExpansionOutcome =
    placeMemberInCompanion(
      input,
      generatedType,
      companionHasDirectTypeNamed(_, generatedType.name),
      preserveExisting = conflictPolicy == CompanionTypeConflictPolicy.PreserveExisting,
      ExpansionDiagnostic(
        s"generated companion type `${generatedType.name}` conflicts with existing direct companion type member `${generatedType.name}` for `${input.className}`",
        mostSpecificCurrentAnnotationPosition(input)
      )
    )

  private def placeMemberInCompanion(
      input: ExpansionInput,
      generatedMember: MemberDef,
      hasDirectConflict: ModuleDef => Boolean,
      preserveExisting: Boolean,
      conflictDiagnostic: => ExpansionDiagnostic
  )(using Context): ExpansionOutcome =
    input.annotatedClass.rhs match
      case _: Template =>
        input.existingCompanion match
          case Some(existingCompanion) if hasDirectConflict(existingCompanion) =>
            if preserveExisting then
              structured(
                stripCurrentAnnotation(input),
                companion = Some(existingCompanion)
              )
            else
              rejected(conflictDiagnostic, input.annotatedClass)
          case Some(existingCompanion) =>
            structured(
              stripCurrentAnnotation(input),
              companion = Some(
                mergeMemberIntoCompanion(existingCompanion, generatedMember)
              )
            )
          case None =>
            structured(
              stripCurrentAnnotation(input),
              companion = Some(
                makeCompanionWithMember(
                  input.annotatedClass.name,
                  generatedMember,
                  input.annotatedClass.source
                )
              )
            )
      case _ =>
        ExpansionOutcome.NotApplicable

  /** Generate one top-level sibling class with a string-returning method.
    *
    * This helper accepts only the current narrow top-level class/template
    * fixture. It validates a non-empty simple sibling type name and rejects a
    * syntactic top-level conflict before constructing any output. Rejection
    * returns the original annotated class as fallback, without consuming its
    * current annotation or emitting a partial sibling. The plugin's separate
    * error-recovery layer reports the diagnostic and strips handled annotations
    * from that fallback before compilation continues.
    *
    * On success, only `input.currentAnnotation` is removed from the primary
    * class. Other modifiers, annotations, constructor data, parents, template
    * shape, members, and source position remain on that original tree. The
    * result order is exactly the stripped primary class followed by the new
    * sibling class. The sibling uses the annotated class source as its position
    * basis and an empty raw template whose ordinary parents are supplied by
    * typer. Conflict detection and tree construction are syntactic and
    * compiler-version-sensitive.
    */
  def addStringMethodSiblingClass(
      input: ExpansionInput,
      siblingClassName: String,
      methodName: String,
      value: String
  )(using Context): ExpansionOutcome =
    input.annotatedClass.rhs match
      case _: Template =>
        val rejectionPosition = mostSpecificCurrentAnnotationPosition(input)
        if !isUsableSimpleTypeName(siblingClassName) then
          rejected(
            ExpansionDiagnostic(
              s"generated sibling name `$siblingClassName` is not a usable simple type name for `${input.className}`; this case is currently unsupported",
              rejectionPosition
            ),
            input.annotatedClass
          )
        else if input.topLevelNames.contains(siblingClassName) then
          rejected(
            ExpansionDiagnostic(
              s"generated sibling `$siblingClassName` already exists; @${input.annotationName} cannot generate sibling for `${input.className}` because this top-level conflict is currently unsupported",
              rejectionPosition
            ),
            input.annotatedClass
          )
        else
          val strippedClass = stripCurrentAnnotation(input)
          val sibling =
            makeSiblingWithStringMethod(
              siblingClassName,
              methodName,
              value,
              input.annotatedClass.source
            )

          structured(
            strippedClass,
            additionalTopLevelDefinitions = List(sibling)
          )
      case _ =>
        ExpansionOutcome.NotApplicable

  private def stripCurrentAnnotation(input: ExpansionInput)(using Context): TypeDef =
    input.currentAnnotation match
      case Some(currentAnnotation) =>
        stripAnnotationByIdentity(input.annotatedClass, currentAnnotation)
      case None =>
        // ASSUMPTION
        // Older direct helper callers may not populate `currentAnnotation`.
        // Keep the previous conservative cleanup fallback for those callers,
        // while plugin-orchestrated expansion supplies the exact raw tree.
        stripAnnotations(input.annotatedClass)

  private def stripAnnotationByIdentity(typeDef: TypeDef, currentAnnotation: untpd.Tree)(using Context): TypeDef =
    val currentMods = Trees.mods(typeDef)
    val preservedAnnotations = currentMods.annotations.filterNot(_ eq currentAnnotation)
    typeDef.withMods(currentMods.withAnnotations(preservedAnnotations)).asInstanceOf[TypeDef]

  private def stripAnnotations(typeDef: TypeDef)(using Context): TypeDef =
    val currentMods = Trees.mods(typeDef)
    typeDef.withMods(currentMods.withAnnotations(Nil)).asInstanceOf[TypeDef]

  private def classHasMethod(template: Template, methodName: String)(using Context): Boolean =
    template.body(using summon[Context]).exists:
      case defDef: DefDef => defDef.name == termName(methodName)
      case _ => false

  private def mostSpecificCurrentAnnotationPosition(
      input: ExpansionInput
  )(using Context): dotty.tools.dotc.util.SrcPos =
    input.currentAnnotation
      .flatMap(Option(_))
      .map(_.sourcePos)
      .filter(_.span.exists)
      .getOrElse(input.annotatedClass.sourcePos)

  private def makeCompanionWithMember(
      className: TypeName,
      generatedMember: MemberDef,
      source: dotty.tools.dotc.util.SourceFile
  )(using Context): ModuleDef =
    given dotty.tools.dotc.util.SourceFile = source

    // ASSUMPTION
    // This mirrors the internal built-in companion generation shape: an untyped
    // companion with no explicit parents is enough for current top-level test
    // fixtures, and typer supplies the ordinary object parents.
    ModuleDef(className.toTermName, makeTemplate(source, List(generatedMember)))

  private def mergeMemberIntoCompanion(
      existingCompanion: ModuleDef,
      generatedMember: MemberDef
  )(using Context): ModuleDef =
    val existingTemplate = existingCompanion.impl
    val existingBody = existingTemplate.body(using summon[Context])

    val mergedTemplate =
      untpd.cpy.Template(existingTemplate)(
        existingTemplate.constr,
        existingTemplate.parentsOrDerived(using summon[Context]),
        existingTemplate.derived,
        existingTemplate.self,
        existingBody :+ generatedMember
      )

    untpd.cpy.ModuleDef(existingCompanion)(existingCompanion.name, mergedTemplate)

  private def companionHasDirectMemberNamed(
      existingCompanion: ModuleDef,
      methodName: TermName
  )(using Context): Boolean =
    existingCompanion.impl.body(using summon[Context]).exists:
      case member: MemberDef => member.name == methodName
      case _ => false

  private def companionHasDirectTypeNamed(
      existingCompanion: ModuleDef,
      generatedTypeName: TypeName
  )(using Context): Boolean =
    existingCompanion.impl.body(using summon[Context]).exists:
      case member: TypeDef => member.name == generatedTypeName
      case _ => false

  private def makeSiblingWithStringMethod(
      siblingClassName: String,
      methodName: String,
      value: String,
      source: dotty.tools.dotc.util.SourceFile
  )(using Context): TypeDef =
    given dotty.tools.dotc.util.SourceFile = source

    // ASSUMPTION
    // This mirrors the built-in sibling fixture: an empty raw class template is
    // sufficient here, and typer supplies the ordinary class parents.
    TypeDef(
      typeName(siblingClassName),
      makeTemplate(source, List(stringReturningMethod(methodName, value, source)))
    )

  private def isUsableSimpleTypeName(value: String): Boolean =
    value.nonEmpty &&
      value == value.trim &&
      (value.head.isLetter || value.head == '_' || value.head == '$') &&
      value.tail.forall(character => character.isLetterOrDigit || character == '_' || character == '$') &&
      !ScalaKeywords.contains(value)

  private val ScalaKeywords = Set(
    "abstract", "case", "catch", "class", "def", "do", "else", "enum",
    "export", "extends", "false", "final", "finally", "for", "forSome",
    "given", "if", "implicit", "import", "lazy", "macro", "match", "new",
    "null", "object", "opaque", "open", "override", "package", "private",
    "protected", "return", "sealed", "super", "then", "this", "throw",
    "trait", "transparent", "true", "try", "type", "using", "val", "var",
    "while", "with", "yield"
  )

  private def makeTemplate(
      source: dotty.tools.dotc.util.SourceFile,
      body: List[untpd.Tree]
  )(using Context): Template =
    given dotty.tools.dotc.util.SourceFile = source

    // MAY DEPEND ON SCALA VERSION
    // Raw Template construction is compiler-internal and intentionally narrow.
    Template(
      emptyConstructor,
      Nil,
      Nil,
      EmptyValDef,
      body
    )
