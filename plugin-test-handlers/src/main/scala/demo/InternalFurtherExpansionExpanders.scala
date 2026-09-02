package demo

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.{
  AnnotationApplication,
  AnnotationTermArgument,
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander,
  StructuredExpansionOutput
}
import paradise3.api.helpers.ExpansionHelpers

import java.lang.reflect.InvocationTargetException

private object InternalFurtherExpansionFixtureBridge:
  private val BridgeClassName =
    "macroparadise.InternalFurtherExpansionRequests$"

  def request(
      input: ExpansionInput,
      annotationName: String,
      typeArguments: List[untpd.Tree] = Nil,
      termArguments: List[untpd.Tree] = Nil
  )(using Context): Unit =
    val rawApplication = application(
      input,
      annotationName,
      typeArguments,
      termArguments
    )

    enqueue(annotationName, rawApplication)

  def application(
      input: ExpansionInput,
      annotationName: String,
      typeArguments: List[untpd.Tree] = Nil,
      termArguments: List[untpd.Tree] = Nil
  )(using Context): untpd.Tree =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    val annotationType: untpd.Tree =
      val base = untpd.Ident(typeName(annotationName))
      if typeArguments.isEmpty then base
      else untpd.AppliedTypeTree(base, typeArguments)
    untpd.Apply(
      untpd.Select(
        untpd.New(annotationType),
        termName("<init>")
      ),
      termArguments
    )

  private def enqueue(annotationName: String, rawApplication: untpd.Tree): Unit =
    val bridgeClass = Class.forName(BridgeClassName)
    val module = bridgeClass.getField("MODULE$").get(null)
    val enqueue =
      bridgeClass.getMethod(
        "enqueueFixtureRequest",
        classOf[String],
        classOf[untpd.Tree]
      )
    try enqueue.invoke(module, annotationName, rawApplication)
    catch
      case error: InvocationTargetException =>
        throw Option(error.getCause).getOrElse(error)

private object InternalStructuredR2FixtureBridge:
  private val BridgeClassName =
    "macroparadise.InternalFurtherExpansionRequests$"

  def directive(
      input: ExpansionInput,
      annotationName: String,
      typeArguments: List[untpd.Tree] = Nil,
      termArguments: List[untpd.Tree] = Nil
  )(using Context): Unit =
    val rawApplication = InternalFurtherExpansionFixtureBridge.application(
      input,
      annotationName,
      typeArguments,
      termArguments
    )
    emit(annotationName, rawApplication, "generated/delegated")

  def mismatchedDirective(
      input: ExpansionInput,
      annotationName: String,
      rawAnnotationName: String
  )(using Context): Unit =
    emit(
      annotationName,
      InternalFurtherExpansionFixtureBridge.application(
        input,
        rawAnnotationName
      ),
      "generated/delegated"
    )

  def malformedRawDirective(
      annotationName: String
  )(using Context): Unit =
    emit(
      annotationName,
      untpd.Ident(typeName(annotationName)),
      "generated/delegated"
    )

  def nullRawDirective(annotationName: String): Unit =
    emit(annotationName, null, "generated/delegated")

  def unsupportedProvenanceDirective(
      input: ExpansionInput,
      annotationName: String
  )(using Context): Unit =
    emit(
      annotationName,
      InternalFurtherExpansionFixtureBridge.application(input, annotationName),
      "source"
    )

  def sourceObjectDirective(input: ExpansionInput): Unit =
    emit(
      input.annotationName,
      input.currentAnnotation.orNull,
      "generated/delegated"
    )

  private def emit(
      annotationName: String,
      rawApplication: untpd.Tree,
      provenance: String
  ): Unit =
    val bridgeClass = Class.forName(BridgeClassName)
    val module = bridgeClass.getField("MODULE$").get(null)
    val emitDirective =
      bridgeClass.getMethod(
        "emitFixtureStructuredDirective",
        classOf[String],
        classOf[untpd.Tree],
        classOf[String]
      )
    try
      emitDirective.invoke(module, annotationName, rawApplication, provenance)
    catch
      case error: InvocationTargetException =>
        throw Option(error.getCause).getOrElse(error)

private object InternalFurtherExpansionFixtureTrees:
  def withoutCurrent(input: ExpansionInput)(using Context): untpd.TypeDef =
    val mods = Trees.mods(input.annotatedClass)
    input.annotatedClass
      .withMods(
        mods.withAnnotations(
          mods.annotations.filterNot(annotation =>
            input.currentAnnotation.exists(_ eq annotation)
          )
        )
      )
      .asInstanceOf[untpd.TypeDef]

  def additionalClass(
      input: ExpansionInput,
      suffix: String
  )(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    untpd.TypeDef(
      typeName(s"${input.className}$suffix"),
      untpd.Template(
        untpd.emptyConstructor,
        Nil,
        Nil,
        untpd.EmptyValDef,
        Nil
      )
    )

  def withAdditional(
      input: ExpansionInput,
      suffix: String
  )(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(
        withoutCurrent(input),
        input.existingCompanion,
        List(additionalClass(input, suffix))
      )
    )

  def withCompanionAndAdditional(
      input: ExpansionInput,
      companionMethod: String,
      suffix: String
  )(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToCompanion(
      input,
      companionMethod,
      input.className
    ) match
      case ExpansionOutcome.Structured(output) =>
        ExpansionOutcome.Structured(
          output.copy(
            additionalTopLevelDefinitions =
              output.additionalTopLevelDefinitions :+ additionalClass(input, suffix)
          )
        )
      case other => other

  def unchanged(input: ExpansionInput): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.annotatedClass))

  def withFreshHandledAnnotation(
      input: ExpansionInput,
      annotationName: String
  )(using Context): ExpansionOutcome =
    val mods = Trees.mods(input.annotatedClass)
    val fresh =
      InternalFurtherExpansionFixtureBridge.application(input, annotationName)
    ExpansionOutcome.Expanded(
      List(
        input.annotatedClass.withMods(
          mods.withAnnotations(mods.annotations :+ fresh)
        )
      )
    )

  def rejected(input: ExpansionInput, detail: String)(using Context): ExpansionOutcome =
    ExpansionHelpers.rejected(detail, input.annotatedClass)

  def requiredIntArgument(input: ExpansionInput)(using Context): Either[String, Int] =
    AnnotationApplication.fromInput(input) match
      case Left(diagnostic) => Left(diagnostic.message)
      case Right(application) =>
        application.termArguments match
          case AnnotationTermArgument.Positional(
                untpd.Literal(Constant(value: Int)),
                _
              ) :: Nil => Right(value)
          case other => Left(s"expected one raw integer argument, found $other")

final class InternalR1AExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1A"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.className match
      case "R1MultiFifo" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1B")
        InternalFurtherExpansionFixtureBridge.request(input, "r1C")
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")
      case "R1Arguments" =>
        InternalFurtherExpansionFixtureBridge.request(
          input,
          "r1B",
          typeArguments = List(untpd.Ident(typeName("String"))),
          termArguments = List(
            untpd.Literal(Constant("positional")),
            untpd.NamedArg(termName("flag"), untpd.Literal(Constant(true)))
          )
        )
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")
      case "R1Companion" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1B")
        ExpansionHelpers.addStringMethodToCompanion(
          input,
          "r1ACompanionValue",
          "A"
        )
      case "R1Additional" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1B")
        InternalFurtherExpansionFixtureTrees.withAdditional(input, "R1AExtra")
      case "R1LateFailure" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1LateFailure")
        InternalFurtherExpansionFixtureTrees.withCompanionAndAdditional(
          input,
          "r1ATentativeCompanion",
          "R1TentativeExtra"
        )
      case "R1Malformed" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1Malformed")
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")
      case "R1Unknown" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1Unavailable")
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")
      case "R1Excluded" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1Restricted")
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")
      case "R1StandaloneRequested" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1Standalone")
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")
      case "R1FreshHandled" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1FreshHandled")
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")
      case _ =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1B")
        ExpansionHelpers.addStringMethodToClass(input, "r1AValue", "A")

final class InternalR1BExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1B"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.className match
      case "R1FiniteABC" | "R1ChainedFifo" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r1C")
        ExpansionHelpers.addStringMethodToClass(input, "r1BValue", "B")
      case "R1Arguments" =>
        AnnotationApplication.fromInput(input) match
          case Right(application)
              if application.typeArguments match
                case (identifier: untpd.Ident) :: Nil =>
                  identifier.name.toString == "String"
                case _ => false
              =>
            application.termArguments match
              case AnnotationTermArgument.Positional(
                    untpd.Literal(Constant("positional")),
                    _
                  ) :: AnnotationTermArgument.Named(
                    "flag",
                    untpd.Literal(Constant(true)),
                    _
                  ) :: Nil =>
                ExpansionHelpers.addStringMethodToClass(
                  input,
                  "r1ArgumentsObserved",
                  "String|positional|true"
                )
              case other =>
                InternalFurtherExpansionFixtureTrees.rejected(
                  input,
                  s"R1 raw term arguments changed: $other"
                )
          case Right(other) =>
            InternalFurtherExpansionFixtureTrees.rejected(
              input,
              s"R1 raw type arguments changed: ${other.typeArguments}"
            )
          case Left(diagnostic) =>
            InternalFurtherExpansionFixtureTrees.rejected(
              input,
              diagnostic.message
            )
      case "R1Companion" =>
        if input.existingCompanion.isEmpty then
          InternalFurtherExpansionFixtureTrees.rejected(
            input,
            "generated R1 B did not receive the latest companion"
          )
        else
          ExpansionHelpers.addStringMethodToCompanion(
            input,
            "r1BCompanionValue",
            "B"
          )
      case "R1Additional" =>
        InternalFurtherExpansionFixtureTrees.withAdditional(input, "R1BExtra")
      case _ =>
        ExpansionHelpers.addStringMethodToClass(input, "r1BValue", "B")

final class InternalR1CExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1C"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(input, "r1CValue", "C")

final class InternalR1SelfExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1Self"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureBridge.request(input, "r1Self")
    ExpansionHelpers.addStringMethodToClass(input, "r1SelfValue", "self")

final class InternalR1MutualSeedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1MutualSeed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureBridge.request(input, "r1MutualA")
    ExpansionHelpers.addStringMethodToClass(input, "r1MutualSeedValue", "seed")

final class InternalR1MutualAExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1MutualA"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureBridge.request(input, "r1MutualB")
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR1MutualBExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1MutualB"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureBridge.request(input, "r1MutualA")
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR1ChangingSeedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1ChangingSeed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureBridge.request(
      input,
      "r1Changing",
      termArguments = List(untpd.Literal(Constant(0)))
    )
    ExpansionHelpers.addStringMethodToClass(input, "r1ChangingSeedValue", "seed")

final class InternalR1ChangingExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1Changing"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.requiredIntArgument(input) match
      case Right(index) =>
        if index < 2 then
          InternalFurtherExpansionFixtureBridge.request(
            input,
            "r1Changing",
            termArguments = List(untpd.Literal(Constant(index + 1)))
          )
        ExpansionHelpers.addStringMethodToClass(
          input,
          s"r1Changed$index",
          index.toString
        )
      case Left(detail) =>
        InternalFurtherExpansionFixtureTrees.rejected(input, detail)

final class InternalR1BudgetSeedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1BudgetSeed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureBridge.request(
      input,
      "r1Budget",
      termArguments = List(untpd.Literal(Constant(0)))
    )
    ExpansionHelpers.addStringMethodToClass(input, "r1BudgetSeedValue", "seed")

final class InternalR1BudgetExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1Budget"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.requiredIntArgument(input) match
      case Right(index) =>
        InternalFurtherExpansionFixtureBridge.request(
          input,
          "r1Budget",
          termArguments = List(untpd.Literal(Constant(index + 1)))
        )
        ExpansionHelpers.addStringMethodToClass(
          input,
          s"r1Budget$index",
          index.toString
        )
      case Left(detail) =>
        InternalFurtherExpansionFixtureTrees.rejected(input, detail)

final class InternalR1LateFailureExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1LateFailure"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    if input.existingCompanion.isEmpty then
      InternalFurtherExpansionFixtureTrees.rejected(
        input,
        "late R1 handler did not receive tentative companion state"
      )
    else
      throw new IllegalStateException("intentional generated R1 late failure")

final class InternalR1MalformedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1Malformed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(Nil)

final class InternalR1RestrictedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1Restricted"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR1StandaloneExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1Standalone"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR1StandaloneGeneratorExpander
    extends ParadiseAnnotationExpander:
  val annotationName: String = "r1StandaloneGenerator"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureBridge.request(input, "r1B")
    ExpansionHelpers.addStringMethodToClass(
      input,
      "r1StandaloneGeneratorValue",
      "standalone"
    )

final class InternalR1FreshHandledExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r1FreshHandled"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.withFreshHandledAnnotation(input, "r1B")

final class InternalR2AExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2A"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.className match
      case "R2EmptyName" =>
        InternalStructuredR2FixtureBridge.mismatchedDirective(input, "", "r2B")
      case "R2NameMismatch" =>
        InternalStructuredR2FixtureBridge.mismatchedDirective(input, "r2B", "r2C")
      case "R2MalformedRaw" =>
        InternalStructuredR2FixtureBridge.malformedRawDirective("r2B")
      case "R2NullRaw" =>
        InternalStructuredR2FixtureBridge.nullRawDirective("r2B")
      case "R2UnsupportedProvenance" =>
        InternalStructuredR2FixtureBridge.unsupportedProvenanceDirective(
          input,
          "r2B"
        )
      case "R2SourceObjectReuse" =>
        InternalStructuredR2FixtureBridge.sourceObjectDirective(input)
      case "R2MultiFifo" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2B")
        InternalStructuredR2FixtureBridge.directive(input, "r2C")
      case "R2Arguments" =>
        InternalStructuredR2FixtureBridge.directive(
          input,
          "r2B",
          typeArguments = List(untpd.Ident(typeName("String"))),
          termArguments = List(
            untpd.Literal(Constant(1)),
            untpd.Literal(Constant("a")),
            untpd.NamedArg(
              termName("flag"),
              untpd.Literal(Constant(true))
            )
          )
        )
      case "R2Companion" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2B")
        return ExpansionHelpers.addStringMethodToCompanion(
          input,
          "r2ACompanionValue",
          "A"
        )
      case "R2Additional" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2B")
        return InternalFurtherExpansionFixtureTrees.withAdditional(
          input,
          "R2AExtra"
        )
      case "R2LateFailure" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2LateFailure")
        return InternalFurtherExpansionFixtureTrees.withCompanionAndAdditional(
          input,
          "r2ATentativeCompanion",
          "R2TentativeExtra"
        )
      case "R2Unknown" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2Unavailable")
      case "R2Excluded" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2Restricted")
      case "R2StandaloneRequested" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2Standalone")
      case "R2FreshHandled" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2FreshHandled")
      case "R2MalformedOutput" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2Malformed")
      case "R2MixedR1ThenR2" =>
        InternalFurtherExpansionFixtureBridge.request(input, "r2B")
        InternalStructuredR2FixtureBridge.directive(input, "r2C")
      case "R2MixedR2ThenR1" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2B")
        InternalFurtherExpansionFixtureBridge.request(input, "r2C")
      case _ =>
        InternalStructuredR2FixtureBridge.directive(input, "r2B")
    ExpansionHelpers.addStringMethodToClass(input, "r2AValue", "A")

final class InternalR2BExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2B"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.className match
      case "R2FiniteABC" | "R2ChainedFifo" =>
        InternalStructuredR2FixtureBridge.directive(input, "r2C")
        ExpansionHelpers.addStringMethodToClass(input, "r2BValue", "B")
      case "R2Arguments" =>
        AnnotationApplication.fromInput(input) match
          case Right(application)
              if application.typeArguments match
                case (identifier: untpd.Ident) :: Nil =>
                  identifier.name.toString == "String"
                case _ => false
              =>
            application.termArguments match
              case AnnotationTermArgument.Positional(
                    untpd.Literal(Constant(1)),
                    _
                  ) :: AnnotationTermArgument.Positional(
                    untpd.Literal(Constant("a")),
                    _
                  ) :: AnnotationTermArgument.Named(
                    "flag",
                    untpd.Literal(Constant(true)),
                    _
                  ) :: Nil =>
                ExpansionHelpers.addStringMethodToClass(
                  input,
                  "r2ArgumentsObserved",
                  "String|1|a|true"
                )
              case other =>
                InternalFurtherExpansionFixtureTrees.rejected(
                  input,
                  s"R2 raw term arguments changed: $other"
                )
          case Right(other) =>
            InternalFurtherExpansionFixtureTrees.rejected(
              input,
              s"R2 raw type arguments changed: ${other.typeArguments}"
            )
          case Left(diagnostic) =>
            InternalFurtherExpansionFixtureTrees.rejected(
              input,
              diagnostic.message
            )
      case "R2Companion" =>
        if input.existingCompanion.isEmpty then
          InternalFurtherExpansionFixtureTrees.rejected(
            input,
            "generated R2 B did not receive the latest companion"
          )
        else
          ExpansionHelpers.addStringMethodToCompanion(
            input,
            "r2BCompanionValue",
            "B"
          )
      case "R2Additional" =>
        InternalFurtherExpansionFixtureTrees.withAdditional(input, "R2BExtra")
      case _ =>
        ExpansionHelpers.addStringMethodToClass(input, "r2BValue", "B")

final class InternalR2CExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2C"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(input, "r2CValue", "C")

final class InternalR2SelfExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2Self"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalStructuredR2FixtureBridge.directive(input, "r2Self")
    ExpansionHelpers.addStringMethodToClass(input, "r2SelfValue", "self")

final class InternalR2MutualSeedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2MutualSeed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalStructuredR2FixtureBridge.directive(input, "r2MutualA")
    ExpansionHelpers.addStringMethodToClass(input, "r2MutualSeedValue", "seed")

final class InternalR2MutualAExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2MutualA"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalStructuredR2FixtureBridge.directive(input, "r2MutualB")
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR2MutualBExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2MutualB"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalStructuredR2FixtureBridge.directive(input, "r2MutualA")
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR2ChangingSeedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2ChangingSeed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalStructuredR2FixtureBridge.directive(
      input,
      "r2Changing",
      termArguments = List(untpd.Literal(Constant(0)))
    )
    ExpansionHelpers.addStringMethodToClass(input, "r2ChangingSeedValue", "seed")

final class InternalR2ChangingExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2Changing"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.requiredIntArgument(input) match
      case Right(index) =>
        if index < 2 then
          InternalStructuredR2FixtureBridge.directive(
            input,
            "r2Changing",
            termArguments = List(untpd.Literal(Constant(index + 1)))
          )
        ExpansionHelpers.addStringMethodToClass(
          input,
          s"r2Changed$index",
          index.toString
        )
      case Left(detail) =>
        InternalFurtherExpansionFixtureTrees.rejected(input, detail)

final class InternalR2BudgetSeedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2BudgetSeed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalStructuredR2FixtureBridge.directive(
      input,
      "r2Budget",
      termArguments = List(untpd.Literal(Constant(0)))
    )
    ExpansionHelpers.addStringMethodToClass(input, "r2BudgetSeedValue", "seed")

final class InternalR2BudgetExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2Budget"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.requiredIntArgument(input) match
      case Right(index) =>
        InternalStructuredR2FixtureBridge.directive(
          input,
          "r2Budget",
          termArguments = List(untpd.Literal(Constant(index + 1)))
        )
        ExpansionHelpers.addStringMethodToClass(
          input,
          s"r2Budget$index",
          index.toString
        )
      case Left(detail) =>
        InternalFurtherExpansionFixtureTrees.rejected(input, detail)

final class InternalR2LateFailureExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2LateFailure"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    if input.existingCompanion.isEmpty then
      InternalFurtherExpansionFixtureTrees.rejected(
        input,
        "late R2 handler did not receive tentative companion state"
      )
    else throw new IllegalStateException("intentional generated R2 late failure")

final class InternalR2MalformedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2Malformed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(Nil)

final class InternalR2RestrictedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2Restricted"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR2StandaloneExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2Standalone"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.unchanged(input)

final class InternalR2StandaloneGeneratorExpander
    extends ParadiseAnnotationExpander:
  val annotationName: String = "r2StandaloneGenerator"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalStructuredR2FixtureBridge.directive(input, "r2B")
    ExpansionHelpers.addStringMethodToClass(
      input,
      "r2StandaloneGeneratorValue",
      "standalone"
    )

final class InternalR2FreshHandledExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "r2FreshHandled"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    InternalFurtherExpansionFixtureTrees.withFreshHandledAnnotation(input, "r2B")
