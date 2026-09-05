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

  /** Place an already-authored batch of concrete definitions in the current
    * primary Template.
    *
    * The caller owns authoring and lowering. This helper appends the exact
    * supplied raw members after the existing direct body while preserving the
    * primary constructor, parents, self, modifiers, source position, and
    * unrelated annotations. The admitted batch consists only of non-null
    * `DefDef` and `ValDef` values with usable non-constructor term names and a
    * root source attachment or span suitable for direct insertion. A direct
    * same-term-name conflict against the original body or an earlier generated
    * batch member, or a member without that position, rejects the whole
    * operation before any copy is formed. This deliberately rejects pre-typer
    * method overloading rather than attempting typed signature resolution.
    * Target admission and ordinary typing remain plugin-owned.
    */
  def placeMembersInPrimary(
      input: ExpansionInput,
      generatedMembers: List[untpd.MemberDef]
  )(using Context): ExpansionOutcome =
    input.annotatedClass.rhs match
      case template: Template =>
        val originalBody = template.body(using summon[Context])
        validateGeneratedTermMembers(input, generatedMembers, originalBody, "primary") match
          case Left(diagnostic) => rejected(diagnostic, input.annotatedClass)
          case Right(validatedMembers) =>
            val strippedPrimary = stripCurrentAnnotation(input)
            val rewrittenTemplate =
              untpd.cpy.Template(template)(
                template.constr,
                template.parentsOrDerived(using summon[Context]),
                template.derived,
                template.self,
                originalBody ++ validatedMembers
              )
            structured(
              untpd.cpy.TypeDef(strippedPrimary)(
                strippedPrimary.name,
                rewrittenTemplate
              )
            )
      case _ => ExpansionOutcome.NotApplicable

  /** Place an already-authored batch of concrete definitions in the object
    * companion selected by the current plugin lease.
    *
    * This is the generic `DefDef`/`ValDef` counterpart to the existing narrow
    * companion conveniences. It validates the whole batch before creating or
    * copying a companion, rejects members without a usable root source
    * attachment or span, direct term-name conflicts, and pre-typer overloads
    * atomically, preserves an existing companion's complete Template shape and
    * body order, and appends the exact supplied members. The caller owns
    * authoring/lowering; the plugin continues to own companion selection,
    * leasing, output validation, and rollback.
    */
  def placeMembersInCompanion(
      input: ExpansionInput,
      generatedMembers: List[untpd.MemberDef]
  )(using Context): ExpansionOutcome =
    input.annotatedClass.rhs match
      case _: Template =>
        val existingBody =
          input.existingCompanion
            .map(_.impl.body(using summon[Context]))
            .getOrElse(Nil)
        validateGeneratedTermMembers(input, generatedMembers, existingBody, "companion") match
          case Left(diagnostic) => rejected(diagnostic, input.annotatedClass)
          case Right(validatedMembers) =>
            val companion = input.existingCompanion match
              case Some(existingCompanion) =>
                mergeMembersIntoCompanion(existingCompanion, validatedMembers)
              case None =>
                makeCompanionWithMembers(
                  input.annotatedClass.name,
                  validatedMembers,
                  input.annotatedClass.source
                )
            structured(
              stripCurrentAnnotation(input),
              companion = Some(companion)
            )
      case _ => ExpansionOutcome.NotApplicable

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

  /** Prepare and atomically install one caller-lowered direct `Self` member on
    * an ordinary zero-parameter trait.
    *
    * Target, raw self-shape, and direct `Self` conflict checks complete before
    * `lowerGeneratedType` is invoked. The callback receives only the exact
    * selected alias name, its origin, and a source position; it receives no raw
    * template/self tree and no retained compiler context. On success this helper
    * prepends the exact returned `TypeDef`, preserves every original body member
    * identity/order, installs the selected alias when needed, and removes only
    * `input.currentAnnotation`.
    *
    * This is a bounded compiler-version-sensitive lifecycle helper. It does not
    * construct or interpret generated bounds, typecheck, resolve symbols, edit a
    * companion, repair owners, or expose arbitrary primary/template mutation.
    */
  def addPreparedSelfTypeToTrait(
      input: ExpansionInput
  )(
      lowerGeneratedType: TraitSelfPreparation => untpd.TypeDef
  )(using Context): ExpansionOutcome =
    if input == null || input.annotatedClass == null then
      throw new IllegalArgumentException(
        "addPreparedSelfTypeToTrait requires a non-null ExpansionInput and annotated class"
      )
    else
      plainZeroParameterTraitDiagnostic(input) match
        case Some(diagnostic) =>
          rejected(diagnostic, input.annotatedClass)
        case None =>
          input.annotatedClass.rhs match
            case template: Template =>
              directSelfConflict(template) match
                case Some(conflict) =>
                  rejected(
                    ExpansionDiagnostic(
                      s"trait `${input.className}` already contains direct type member `Self`; bounded self preparation requires deterministic rejection",
                      usableTreePosition(conflict, mostSpecificCurrentAnnotationPosition(input))
                    ),
                    input.annotatedClass
                  )
                case None =>
                  prepareTraitSelf(input, template) match
                    case Left(diagnostic) =>
                      rejected(diagnostic, input.annotatedClass)
                    case Right((preparation, selectedSelf)) =>
                      if lowerGeneratedType == null then
                        throw new IllegalArgumentException(
                          "addPreparedSelfTypeToTrait requires a non-null lowering callback after successful preflight"
                        )
                      val generatedType = lowerGeneratedType(preparation)
                      if generatedType == null then
                        rejected(
                          ExpansionDiagnostic(
                            s"trait `${input.className}` self preparation returned a null generated `Self` TypeDef",
                            preparation.pos
                          ),
                          input.annotatedClass
                        )
                      else if generatedType.name != typeName("Self") then
                        rejected(
                          ExpansionDiagnostic(
                            s"trait `${input.className}` self preparation requires generated type name `Self`; found `${generatedType.name}`",
                            usableTreePosition(generatedType, preparation.pos)
                          ),
                          input.annotatedClass
                        )
                      else
                        val originalBody = template.body(using summon[Context])
                        val rewrittenTemplate =
                          untpd.cpy.Template(template)(
                            template.constr,
                            template.parentsOrDerived(using summon[Context]),
                            template.derived,
                            selectedSelf,
                            generatedType :: originalBody
                          )
                        val strippedPrimary = stripCurrentAnnotation(input)
                        structured(
                          untpd.cpy.TypeDef(strippedPrimary)(
                            strippedPrimary.name,
                            rewrittenTemplate
                          )
                        )
            case _ =>
              rejected(
                ExpansionDiagnostic(
                  s"trait `${input.className}` has an unsupported non-template primary shape for bounded self preparation",
                  mostSpecificCurrentAnnotationPosition(input)
                ),
                input.annotatedClass
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
    * A method that would actually be inserted must already carry a usable root
    * source attachment or span; Macro-Paradise rejects rather than positions or
    * repairs it.
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
      ),
      generatedMemberInsertionDiagnostic(input, generatedMethod)
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

  /** Place an already-created raw module in the annotated class's companion.
    *
    * The caller owns complete construction and lowering of `generatedModule`;
    * this helper inserts that exact `ModuleDef` without rebuilding, parsing,
    * inspecting, validating, or interpreting its body. Placement is syntactic
    * and pre-typer. A conflict is deliberately limited to a direct raw
    * companion `MemberDef` with the same `TermName`, including a `ModuleDef`,
    * `DefDef`, or `ValDef`. A direct same-spelling `TypeDef` remains in the type
    * namespace, while nested/non-direct definitions and semantic resolution are
    * outside this contract.
    *
    * `PreserveExisting` returns successful structured output with the exact
    * existing companion unchanged. `Reject` returns one module-specific
    * diagnostic and the original annotated class fallback without a partial
    * companion. Successful output removes only `input.currentAnnotation` when
    * present and otherwise retains the established direct-caller clear-all
    * fallback.
    *
    * This compiler-version-sensitive experimental helper accepts only
    * `untpd.ModuleDef`; it is not arbitrary `MemberDef` placement or a module
    * authoring facade.
    */
  def addModuleToCompanion(
      input: ExpansionInput,
      generatedModule: untpd.ModuleDef,
      conflictPolicy: CompanionModuleConflictPolicy
  )(using Context): ExpansionOutcome =
    placeMemberInCompanion(
      input,
      generatedModule,
      companionHasDirectMemberNamed(_, generatedModule.name),
      preserveExisting = conflictPolicy == CompanionModuleConflictPolicy.PreserveExisting,
      ExpansionDiagnostic(
        s"generated companion module `${generatedModule.name}` conflicts with existing direct companion term member `${generatedModule.name}` for `${input.className}`",
        mostSpecificCurrentAnnotationPosition(input)
      )
    )

  private def placeMemberInCompanion(
      input: ExpansionInput,
      generatedMember: MemberDef,
      hasDirectConflict: ModuleDef => Boolean,
      preserveExisting: Boolean,
      conflictDiagnostic: => ExpansionDiagnostic,
      insertionDiagnostic: => Option[ExpansionDiagnostic] = None
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
            insertionDiagnostic match
              case Some(diagnostic) => rejected(diagnostic, input.annotatedClass)
              case None =>
                structured(
                  stripCurrentAnnotation(input),
                  companion = Some(
                    mergeMemberIntoCompanion(existingCompanion, generatedMember)
                  )
                )
          case None =>
            insertionDiagnostic match
              case Some(diagnostic) => rejected(diagnostic, input.annotatedClass)
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

  private def validateGeneratedTermMembers(
      input: ExpansionInput,
      generatedMembers: List[MemberDef] | Null,
      existingBody: List[Tree],
      targetRole: String
  )(using Context): Either[ExpansionDiagnostic, List[MemberDef]] =
    val rejectionPosition = mostSpecificCurrentAnnotationPosition(input)
    Option(generatedMembers) match
      case None =>
        Left(
          ExpansionDiagnostic(
            s"generated member batch for `${input.className}` must be a non-null List",
            rejectionPosition
          )
        )
      case Some(members) =>
        if members.isEmpty then
          Left(
            ExpansionDiagnostic(
              s"generated member batch for `${input.className}` must contain at least one untpd.DefDef or untpd.ValDef",
              rejectionPosition
            )
          )
        else
          def loop(
            remaining: List[MemberDef],
            index: Int,
            generatedNames: Set[TermName]
          ): Either[ExpansionDiagnostic, List[MemberDef]] =
            remaining match
              case Nil => Right(members)
              case rawMember :: tail =>
                Option(rawMember) match
                  case Some(member: DefDef) =>
                    validateGeneratedTermMember(
                      input,
                      member,
                      member.name,
                      index,
                      existingBody,
                      generatedNames,
                      targetRole
                    ).flatMap: _ =>
                      loop(tail, index + 1, generatedNames + member.name)
                  case Some(member: ValDef) =>
                    validateGeneratedTermMember(
                      input,
                      member,
                      member.name,
                      index,
                      existingBody,
                      generatedNames,
                      targetRole
                    ).flatMap: _ =>
                      loop(tail, index + 1, generatedNames + member.name)
                  case Some(other) =>
                    Left(
                      ExpansionDiagnostic(
                        s"generated member batch entry $index for `${input.className}` has unsupported raw kind `${other.getClass.getName}`; only untpd.DefDef and untpd.ValDef are admitted",
                        usableTreePosition(other, rejectionPosition)
                      )
                    )
                  case None =>
                    Left(
                      ExpansionDiagnostic(
                        s"generated member batch entry $index for `${input.className}` is null; only untpd.DefDef and untpd.ValDef are admitted",
                        rejectionPosition
                      )
                    )

          loop(members, 0, Set.empty)

  private def validateGeneratedTermMember(
      input: ExpansionInput,
      member: MemberDef,
      name: TermName,
      index: Int,
      existingBody: List[Tree],
      generatedNames: Set[TermName],
      targetRole: String
  )(using Context): Either[ExpansionDiagnostic, Unit] =
    val decodedName = name.toString
    val rejectionPosition = mostSpecificCurrentAnnotationPosition(input)
    if !member.source.exists && !member.span.exists then
      Left(generatedMemberPositionDiagnostic(input, member))
    else if decodedName.isEmpty || decodedName == "<init>" || decodedName == "<clinit>" then
      Left(
        ExpansionDiagnostic(
          s"generated member batch entry $index for `${input.className}` has unusable direct term name `$decodedName`",
          usableTreePosition(member, rejectionPosition)
        )
      )
    else if existingBody.exists:
        case direct: DefDef => direct.name == name
        case direct: ValDef => direct.name == name
        case direct: ModuleDef => direct.name == name
        case _ => false
    then
      Left(
        ExpansionDiagnostic(
          s"generated $targetRole member `$decodedName` conflicts with existing direct $targetRole term member `$decodedName` for `${input.className}`",
          rejectionPosition
        )
      )
    else if generatedNames.contains(name) then
      Left(
        ExpansionDiagnostic(
          s"generated member batch for `${input.className}` contains duplicate direct term name `$decodedName`; pre-typer overload resolution is not attempted",
          usableTreePosition(member, rejectionPosition)
        )
      )
    else Right(())

  private def generatedMemberInsertionDiagnostic(
      input: ExpansionInput,
      member: MemberDef
  )(using Context): Option[ExpansionDiagnostic] =
    Option.when(!member.source.exists && !member.span.exists)(
      generatedMemberPositionDiagnostic(input, member)
    )

  private def generatedMemberPositionDiagnostic(
      input: ExpansionInput,
      member: MemberDef
  )(using Context): ExpansionDiagnostic =
    ExpansionDiagnostic(
      s"generated member `${member.name}` for `${input.className}` has no usable source position; direct placement requires an insertion-ready positioned DefDef or ValDef",
      mostSpecificCurrentAnnotationPosition(input)
    )

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

  private def plainZeroParameterTraitDiagnostic(
      input: ExpansionInput
  )(using Context): Option[ExpansionDiagnostic] =
    input.annotatedClassView match
      case Left(diagnostic) => Some(diagnostic)
      case Right(view) =>
        val requirement =
          "bounded self preparation requires one ordinary non-case, non-sealed trait with zero type parameters and no constructor/value parameters"
        val rejection =
          if view.definitionKind != AnnotatedClassView.DefinitionKind.Trait then
            Some((s"$requirement; found `${view.definitionKind.toString.toLowerCase} ${view.className}`", view.classPos))
          else if view.modifiers.isCase then
            Some((s"$requirement; case trait `${view.className}` is unsupported", view.classPos))
          else if view.modifiers.isSealed then
            Some((s"$requirement; sealed trait `${view.className}` is unsupported", view.classPos))
          else if view.typeParameters.nonEmpty then
            Some((s"$requirement; found ${view.typeParameters.size} type parameters", view.typeParameters.head.pos))
          else
            view.constructorClauses.find(_.parameters.nonEmpty).map: clause =>
              (s"$requirement; trait constructor/value parameters are unsupported", clause.pos)
        rejection.map:
          case (message, pos) => ExpansionDiagnostic(message, pos)

  private def directSelfConflict(template: Template)(using Context): Option[TypeDef] =
    template.body(using summon[Context])
      .find: tree =>
        tree.isInstanceOf[TypeDef] && tree.asInstanceOf[TypeDef].name == typeName("Self")
      .map(_.asInstanceOf[TypeDef])

  private def prepareTraitSelf(
      input: ExpansionInput,
      template: Template
  )(using Context): Either[ExpansionDiagnostic, (TraitSelfPreparation, ValDef)] =
    val existingSelf = template.self
    if existingSelf.eq(EmptyValDef) then
      val alias = freshSelfAlias(template)
      val replacementType = TypeTree()
      val replacement =
        untpd.ValDef(termName(alias), replacementType, existingSelf.rhs)
          .withMods(Trees.mods(existingSelf))
          .withSpan(input.annotatedClass.span)
          .asInstanceOf[ValDef]
      Right(
        TraitSelfPreparation(
          alias,
          SelfAliasOrigin.Generated,
          mostSpecificCurrentAnnotationPosition(input)
        ) -> replacement
      )
    else if usableNamedSelf(existingSelf) then
      Right(
        TraitSelfPreparation(
          existingSelf.name.toString,
          SelfAliasOrigin.ExistingNamed,
          usableTreePosition(existingSelf, mostSpecificCurrentAnnotationPosition(input))
        ) -> existingSelf
      )
    else
      Left(
        ExpansionDiagnostic(
          s"trait `${input.className}` has an unsupported or malformed raw self declaration for bounded self preparation",
          usableTreePosition(existingSelf, mostSpecificCurrentAnnotationPosition(input))
        )
      )

  private def usableNamedSelf(self: ValDef)(using Context): Boolean =
    val decodedName = self.name.toString
    decodedName.nonEmpty &&
      decodedName != "_" &&
      self.tpt != null &&
      !self.tpt.eq(EmptyTree) &&
      self.rhs != null &&
      self.rhs.eq(EmptyTree)

  private def freshSelfAlias(template: Template)(using Context): String =
    val occupied = template.body(using summon[Context]).iterator.flatMap: tree =>
      tree match
        case member: ValDef => Some(member.name.toString)
        case member: DefDef => Some(member.name.toString)
        case member: ModuleDef => Some(member.name.toString)
        case _ => None
    .toSet
    Iterator
      .from(0)
      .map(index => if index == 0 then "self" else s"self$$$index")
      .find(candidate => !occupied.contains(candidate))
      .get

  private def usableTreePosition(
      tree: untpd.Tree,
      fallback: => dotty.tools.dotc.util.SrcPos
  )(using Context): dotty.tools.dotc.util.SrcPos =
    Option(tree)
      .map(_.sourcePos)
      .filter(_.span.exists)
      .getOrElse(fallback)

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
    makeCompanionWithMembers(className, List(generatedMember), source)

  private def makeCompanionWithMembers(
      className: TypeName,
      generatedMembers: List[MemberDef],
      source: dotty.tools.dotc.util.SourceFile
  )(using Context): ModuleDef =
    given dotty.tools.dotc.util.SourceFile = source

    // ASSUMPTION
    // This mirrors the internal built-in companion generation shape: an untyped
    // companion with no explicit parents is enough for current top-level test
    // fixtures, and typer supplies the ordinary object parents.
    ModuleDef(className.toTermName, makeTemplate(source, generatedMembers))

  private def mergeMemberIntoCompanion(
      existingCompanion: ModuleDef,
      generatedMember: MemberDef
  )(using Context): ModuleDef =
    mergeMembersIntoCompanion(existingCompanion, List(generatedMember))

  private def mergeMembersIntoCompanion(
      existingCompanion: ModuleDef,
      generatedMembers: List[MemberDef]
  )(using Context): ModuleDef =
    val existingTemplate = existingCompanion.impl
    val existingBody = existingTemplate.body(using summon[Context])

    val mergedTemplate =
      untpd.cpy.Template(existingTemplate)(
        existingTemplate.constr,
        existingTemplate.parentsOrDerived(using summon[Context]),
        existingTemplate.derived,
        existingTemplate.self,
        existingBody ++ generatedMembers
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
