package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.{
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ParadiseAnnotationExpander,
  StructuredExpansionOutput
}

private object StructuredOutcomeTrees:
  def renamedPrimary(input: ExpansionInput, name: String)(using Context): TypeDef =
    untpd.cpy.TypeDef(input.annotatedClass)(
      typeName(name),
      input.annotatedClass.rhs
    )

  def unknownAdditional(input: ExpansionInput)(using Context): untpd.Tree =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    Literal(Constant(1))

final class StructuredNullOutputExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredNullOutput"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(null)

final class StructuredNullPrimaryExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredNullPrimary"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(null, None, Nil)
    )

final class StructuredNullCompanionOptionExpander
    extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredNullCompanionOption"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(input.annotatedClass, null, Nil)
    )

final class StructuredNullCompanionExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredNullCompanion"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(
        input.annotatedClass,
        Some(null.asInstanceOf[ModuleDef]),
        Nil
      )
    )

final class StructuredNullAdditionalListExpander
    extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredNullAdditionalList"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(
        input.annotatedClass,
        input.existingCompanion,
        null
      )
    )

final class StructuredNullAdditionalElementExpander
    extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredNullAdditionalElement"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(
        input.annotatedClass,
        None,
        List(null.asInstanceOf[untpd.Tree])
      )
    )

final class StructuredUnknownAdditionalExpander
    extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredUnknownAdditional"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(
        input.annotatedClass,
        None,
        List(StructuredOutcomeTrees.unknownAdditional(input))
      )
    )

final class StructuredTopLevelConflictExpander
    extends ParadiseAnnotationExpander:
  val annotationName: String = "structuredTopLevelConflict"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(
      StructuredExpansionOutput(
        input.annotatedClass,
        None,
        List(StructuredOutcomeTrees.renamedPrimary(input, "KnownConflict"))
      )
    )
