import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object IndependentPrecompiledHandlerPackagedConsumerSpec {
  val CaseCount = 20

  def run(repositoryRoot: File): Unit = {
    def source(path: String): String =
      new String(Files.readAllBytes(new File(repositoryRoot, path).toPath), StandardCharsets.UTF_8)

    val independent = source("plugin-api-handler-contract-probe/positive/IndependentMarkerAndHandler.scala")
    val body = source("plugin-api-handler-contract-probe/body-view/IndependentBodyViewMarkerAndHandler.scala")
    val types = source("plugin-api-handler-contract-probe/type-placement/IndependentTypePlacementMarkerAndHandler.scala")
    val modules = source("plugin-api-handler-contract-probe/module-placement/IndependentModulePlacementMarkerAndHandler.scala")
    val self = source("plugin-api-handler-contract-probe/self-trait/IndependentSelfTraitMarkerAndHandler.scala")
    val union = source("plugin-api-handler-contract-probe/closed-target-union/IndependentClosedTargetUnionMarkerAndHandler.scala")
    val consumer = source("plugin-api-handler-contract-probe/e2e/IndependentPackagedConsumer.scala")

    val checks = Vector(
      "unified handler" -> independent.contains("extends ExpansionHandler"),
      "handler metadata" -> independent.contains("@expander(\"contractprobe.IndependentHandler\")"),
      "explicit admissions" -> independent.contains("val admissions = List("),
      "immutable edit" -> independent.contains("ExpansionEdit.start(input)"),
      "generic primary placement" -> independent.contains("placeMemberInPrimary"),
      "implementation isolation" -> !independent.contains("macroparadise."),
      "ordinary consumer annotation" -> consumer.contains("@IndependentMarker"),
      "generated method consumer" -> consumer.contains("independentHandlerName"),
      "target-neutral body views" -> (body.contains("input.targetTypeStructureView") && body.contains("input.targetBodyView")),
      "body admission" -> body.contains("TwoInvariantUpperBoundedTypeParameters"),
      "generic companion placement" -> body.contains("placeMemberInCompanion"),
      "type member placement" -> (types.contains("placeMemberInCompanion") && types.contains("MemberConflictPolicy.PreserveExisting")),
      "type rejection policy" -> types.contains("MemberConflictPolicy.Reject"),
      "module member placement" -> (modules.contains("placeMemberInCompanion") && modules.contains("MemberConflictPolicy.PreserveExisting")),
      "module rejection policy" -> modules.contains("MemberConflictPolicy.Reject"),
      "trait self edit" -> self.contains("ExpansionHelpers.prepareTraitSelf"),
      "closed admission union" -> (union.contains("OneInvariantUnboundedTypeParameter") && union.contains("TwoInvariantUpperBoundedTypeParameters")),
      "no old target profile" -> !Vector(independent, body, types, modules, self, union).exists(_.contains("ExpansionTargetProfile")),
      "no old companion lease" -> !Vector(independent, body, types, modules, self, union).exists(_.contains("consumesExistingCompanion")),
      "thin artifact inventory" -> (IndependentPrecompiledHandlerPackagedConsumer.expectedCompiledEntries.size == 4)
    )
    checks.foreach { case (name, ok) => require(ok, s"independent packaged model check failed: $name") }
    require(checks.size == CaseCount, s"focused model spec ran ${checks.size}/$CaseCount cases")
  }
}
