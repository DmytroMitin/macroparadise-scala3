package external.descriptorprobe

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander,
  expander
}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.annotation.StaticAnnotation

final class ExplicitSnapshot extends StaticAnnotation

@expander("external.descriptorprobe.MetadataSnapshotHandler")
final class MetadataSnapshot extends StaticAnnotation

final class AnnotationNonFatal extends StaticAnnotation
final class AnnotationLinkage extends StaticAnnotation
final class ProfileNonFatal extends StaticAnnotation
final class ProfileLinkage extends StaticAnnotation
final class CompositionNonFatal extends StaticAnnotation
final class CompositionLinkage extends StaticAnnotation
final class CompanionNonFatal extends StaticAnnotation
final class CompanionLinkage extends StaticAnnotation
final class NullAnnotation extends StaticAnnotation
final class BlankAnnotation extends StaticAnnotation
final class NullProfile extends StaticAnnotation
final class NullComposition extends StaticAnnotation

@expander("external.descriptorprobe.MetadataProfileFailureHandler")
final class MetadataProfileFailure extends StaticAnnotation

@expander("external.descriptorprobe.ExplicitMetadataReuseHandler")
final class ExplicitMetadataReuse extends StaticAnnotation

@expander("external.descriptorprobe.ExplicitMetadataMismatchHandler")
final class ExplicitMetadataWrong extends StaticAnnotation

@expander("external.descriptorprobe.MatchThenMismatchHandler")
final class AMatchFirst extends StaticAnnotation

@expander("external.descriptorprobe.MatchThenMismatchHandler")
final class ZMismatchSecond extends StaticAnnotation

@expander("external.descriptorprobe.MismatchThenMatchHandler")
final class AMismatchFirst extends StaticAnnotation

@expander("external.descriptorprobe.MismatchThenMatchHandler")
final class ZMatchSecond extends StaticAnnotation

@expander("external.descriptorprobe.BothMismatchHandler")
final class ABothMismatch extends StaticAnnotation

@expander("external.descriptorprobe.BothMismatchHandler")
final class ZBothMismatch extends StaticAnnotation

@expander("external.descriptorprobe.StandaloneMismatchHandler")
final class StandaloneMismatch extends StaticAnnotation

@expander("external.descriptorprobe.RunScopedStateHandler")
final class RunScopedMetadata extends StaticAnnotation

@expander("external.descriptorprobe.RunScopedFailureHandler")
final class RunScopedFailure extends StaticAnnotation

private object DescriptorProbeTrace:
  private val PropertyName = "macroparadise.descriptorProbeTrace"

  def record(event: String): Unit =
    Option(System.getProperty(PropertyName)).map(_.trim).filter(_.nonEmpty).foreach: value =>
      Files.writeString(
        Path.of(value),
        s"$event\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )

private abstract class ProbeHandler extends ParadiseAnnotationExpander:
  protected def markerName: String

  def annotationName: String = markerName

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    DescriptorProbeTrace.record(
      s"expand handler=${getClass.getName} existingCompanion=${input.existingCompanion.nonEmpty}"
    )
    ExpansionOutcome.Expanded(input.annotatedClass :: input.existingCompanion.toList)

final class ExplicitSnapshotHandler extends ProbeHandler:
  private var annotationReads = 0
  private var profileReads = 0
  private var companionReads = 0
  private var compositionReads = 0

  protected def markerName: String =
    annotationReads += 1
    DescriptorProbeTrace.record(s"annotationName handler=${getClass.getName} read=$annotationReads")
    if annotationReads == 1 then "ExplicitSnapshot" else "WrongExplicitSnapshot"

  override def targetProfile: ExpansionTargetProfile =
    profileReads += 1
    DescriptorProbeTrace.record(s"targetProfile handler=${getClass.getName} read=$profileReads")
    if profileReads == 1 then ExpansionTargetProfile.RestrictedGenericTraitApply
    else ExpansionTargetProfile.CommonClassOnly

  override def consumesExistingCompanion: Boolean =
    companionReads += 1
    DescriptorProbeTrace.record(s"consumesExistingCompanion handler=${getClass.getName} read=$companionReads")
    companionReads == 1

  override def compositionPolicy: ExpansionCompositionPolicy =
    compositionReads += 1
    DescriptorProbeTrace.record(s"compositionPolicy handler=${getClass.getName} read=$compositionReads")
    if compositionReads == 1 then ExpansionCompositionPolicy.SourceOrdered
    else ExpansionCompositionPolicy.StandaloneOnly

final class MetadataSnapshotHandler extends ProbeHandler:
  private var annotationReads = 0
  private var profileReads = 0
  private var companionReads = 0
  private var compositionReads = 0

  protected def markerName: String =
    annotationReads += 1
    DescriptorProbeTrace.record(s"annotationName handler=${getClass.getName} read=$annotationReads")
    if annotationReads == 1 then "MetadataSnapshot" else "WrongMetadataSnapshot"

  override def targetProfile: ExpansionTargetProfile =
    profileReads += 1
    DescriptorProbeTrace.record(s"targetProfile handler=${getClass.getName} read=$profileReads")
    if profileReads == 1 then ExpansionTargetProfile.RestrictedGenericTraitApply
    else ExpansionTargetProfile.CommonClassOnly

  override def consumesExistingCompanion: Boolean =
    companionReads += 1
    DescriptorProbeTrace.record(s"consumesExistingCompanion handler=${getClass.getName} read=$companionReads")
    companionReads == 1

  override def compositionPolicy: ExpansionCompositionPolicy =
    compositionReads += 1
    DescriptorProbeTrace.record(s"compositionPolicy handler=${getClass.getName} read=$compositionReads")
    if compositionReads == 1 then ExpansionCompositionPolicy.SourceOrdered
    else ExpansionCompositionPolicy.StandaloneOnly

final class AnnotationNonFatalHandler extends ProbeHandler:
  protected def markerName: String = throw IllegalStateException("annotation  nonfatal")

final class AnnotationLinkageHandler extends ProbeHandler:
  protected def markerName: String = throw LinkageError("annotation  linkage")

final class ProfileNonFatalHandler extends ProbeHandler:
  protected val markerName = "ProfileNonFatal"
  override def targetProfile: ExpansionTargetProfile =
    throw IllegalStateException("profile  nonfatal")

final class ProfileLinkageHandler extends ProbeHandler:
  protected val markerName = "ProfileLinkage"
  override def targetProfile: ExpansionTargetProfile =
    throw LinkageError("profile  linkage")

final class CompositionNonFatalHandler extends ProbeHandler:
  protected val markerName = "CompositionNonFatal"
  override def compositionPolicy: ExpansionCompositionPolicy =
    throw IllegalStateException("composition  nonfatal")

final class CompositionLinkageHandler extends ProbeHandler:
  protected val markerName = "CompositionLinkage"
  override def compositionPolicy: ExpansionCompositionPolicy =
    throw LinkageError("composition  linkage")

final class CompanionNonFatalHandler extends ProbeHandler:
  protected val markerName = "CompanionNonFatal"
  override def consumesExistingCompanion: Boolean =
    throw IllegalStateException("companion  nonfatal")

final class CompanionLinkageHandler extends ProbeHandler:
  protected val markerName = "CompanionLinkage"
  override def consumesExistingCompanion: Boolean =
    throw LinkageError("companion  linkage")

final class NullAnnotationHandler extends ProbeHandler:
  protected def markerName: String = null

final class BlankAnnotationHandler extends ProbeHandler:
  protected val markerName = "  \t"

final class NullProfileHandler extends ProbeHandler:
  protected val markerName = "NullProfile"
  override def targetProfile: ExpansionTargetProfile = null

final class NullCompositionHandler extends ProbeHandler:
  protected val markerName = "NullComposition"
  override def compositionPolicy: ExpansionCompositionPolicy = null

final class MetadataProfileFailureHandler extends ProbeHandler:
  protected val markerName = "MetadataProfileFailure"
  override def targetProfile: ExpansionTargetProfile =
    throw IllegalStateException("metadata  profile  failure")

private abstract class BindingProbeHandler(
    declaredAnnotationName: String
) extends ParadiseAnnotationExpander:
  private val instanceId = java.util.UUID.randomUUID().toString
  private var expandOrdinal = 0

  DescriptorProbeTrace.record(s"construct handler=${getClass.getName}")
  DescriptorProbeTrace.record(
    s"instance handler=${getClass.getName} id=$instanceId"
  )

  private var annotationReads = 0
  private var profileReads = 0
  private var companionReads = 0
  private var compositionReads = 0

  def annotationName: String =
    annotationReads += 1
    DescriptorProbeTrace.record(
      s"annotationName handler=${getClass.getName} read=$annotationReads"
    )
    declaredAnnotationName

  override def targetProfile: ExpansionTargetProfile =
    profileReads += 1
    DescriptorProbeTrace.record(
      s"targetProfile handler=${getClass.getName} read=$profileReads"
    )
    ExpansionTargetProfile.CommonClassOnly

  override def consumesExistingCompanion: Boolean =
    companionReads += 1
    DescriptorProbeTrace.record(
      s"consumesExistingCompanion handler=${getClass.getName} read=$companionReads"
    )
    false

  override def compositionPolicy: ExpansionCompositionPolicy =
    compositionReads += 1
    DescriptorProbeTrace.record(
      s"compositionPolicy handler=${getClass.getName} read=$compositionReads"
    )
    ExpansionCompositionPolicy.StandaloneOnly

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    expandOrdinal += 1
    DescriptorProbeTrace.record(
      s"expand handler=${getClass.getName} class=${input.className}"
    )
    DescriptorProbeTrace.record(
      s"expandState handler=${getClass.getName} instance=$instanceId ordinal=$expandOrdinal class=${input.className}"
    )
    ExpansionOutcome.Expanded(List(input.annotatedClass))

final class ExplicitMetadataReuseHandler
    extends BindingProbeHandler("ExplicitMetadataReuse")

final class ExplicitMetadataMismatchHandler
    extends BindingProbeHandler("ExplicitMetadataDeclared")

final class MatchThenMismatchHandler
    extends BindingProbeHandler("AMatchFirst")

final class MismatchThenMatchHandler
    extends BindingProbeHandler("ZMatchSecond")

final class BothMismatchHandler
    extends BindingProbeHandler("DeclaredElsewhere")

final class StandaloneMismatchHandler
    extends BindingProbeHandler("StandaloneDeclared")

final class RunScopedStateHandler
    extends BindingProbeHandler("RunScopedMetadata")

final class RunScopedFailureHandler extends ParadiseAnnotationExpander:
  private val instanceId = java.util.UUID.randomUUID().toString

  DescriptorProbeTrace.record(s"construct handler=${getClass.getName}")
  DescriptorProbeTrace.record(
    s"instance handler=${getClass.getName} id=$instanceId"
  )

  def annotationName: String =
    DescriptorProbeTrace.record(
      s"annotationName handler=${getClass.getName} read=1"
    )
    "RunScopedFailure"

  override def targetProfile: ExpansionTargetProfile =
    DescriptorProbeTrace.record(
      s"targetProfile handler=${getClass.getName} read=1"
    )
    throw IllegalStateException("run scoped descriptor failure")

  override def consumesExistingCompanion: Boolean =
    DescriptorProbeTrace.record(
      s"consumesExistingCompanion handler=${getClass.getName} read=1"
    )
    false

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    DescriptorProbeTrace.record(
      s"expand handler=${getClass.getName} class=${input.className}"
    )
    ExpansionOutcome.Expanded(List(input.annotatedClass))
