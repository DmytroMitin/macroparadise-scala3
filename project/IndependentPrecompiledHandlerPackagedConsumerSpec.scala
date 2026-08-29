import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object IndependentPrecompiledHandlerPackagedConsumerSpec {
  val CaseCount = 116

  def run(repositoryRoot: File): Unit = {
    val independentRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/positive")
    val consumerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e")
    val bodyViewHandlerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/body-view")
    val bodyViewConsumerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-body-view")
    val bodyViewNegativeRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-body-view-negative")
    val typePlacementHandlerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/type-placement")
    val typePlacementConsumerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-type-placement")
    val typePlacementRejectRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-type-placement-reject")
    val modulePlacementHandlerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/module-placement")
    val modulePlacementConsumerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-module-placement")
    val modulePlacementRejectRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-module-placement-reject")
    val selfTraitHandlerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/self-trait")
    val selfTraitConsumerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-self-trait")
    val selfTraitRejectRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e-self-trait-reject")
    val independentSource = new File(independentRoot, "IndependentMarkerAndHandler.scala")
    val consumerSource = new File(consumerRoot, "IndependentPackagedConsumer.scala")
    val bodyViewHandlerSource = new File(bodyViewHandlerRoot, "IndependentBodyViewMarkerAndHandler.scala")
    val bodyViewConsumerSource = new File(bodyViewConsumerRoot, "IndependentBodyViewConsumer.scala")
    val bodyViewNegativeSource = new File(bodyViewNegativeRoot, "UnsupportedBodyViewConsumer.scala")
    val typePlacementHandlerSource = new File(typePlacementHandlerRoot, "IndependentTypePlacementMarkerAndHandler.scala")
    val typePlacementConsumerSource = new File(typePlacementConsumerRoot, "IndependentTypePlacementConsumer.scala")
    val typePlacementRejectSource = new File(typePlacementRejectRoot, "IndependentTypePlacementRejectConsumer.scala")
    val modulePlacementHandlerSource = new File(modulePlacementHandlerRoot, "IndependentModulePlacementMarkerAndHandler.scala")
    val modulePlacementConsumerSource = new File(modulePlacementConsumerRoot, "IndependentModulePlacementConsumer.scala")
    val modulePlacementRejectSource = new File(modulePlacementRejectRoot, "IndependentModulePlacementRejectConsumer.scala")
    val selfTraitHandlerSource = new File(selfTraitHandlerRoot, "IndependentSelfTraitMarkerAndHandler.scala")
    val selfTraitConsumerSource = new File(selfTraitConsumerRoot, "IndependentSelfTraitConsumer.scala")
    val selfTraitRejectSource = new File(selfTraitRejectRoot, "IndependentSelfTraitRejectConsumer.scala")
    val independent = read(independentSource)
    val consumer = read(consumerSource)
    val bodyViewHandler = read(bodyViewHandlerSource)
    val bodyViewConsumer = read(bodyViewConsumerSource)
    val bodyViewNegative = read(bodyViewNegativeSource)
    val typePlacementHandler = read(typePlacementHandlerSource)
    val typePlacementConsumer = read(typePlacementConsumerSource)
    val typePlacementReject = read(typePlacementRejectSource)
    val modulePlacementHandler = read(modulePlacementHandlerSource)
    val modulePlacementConsumer = read(modulePlacementConsumerSource)
    val modulePlacementReject = read(modulePlacementRejectSource)
    val selfTraitHandler = read(selfTraitHandlerSource)
    val selfTraitConsumer = read(selfTraitConsumerSource)
    val selfTraitReject = read(selfTraitRejectSource)
    var completed = 0

    def check(condition: Boolean, message: String): Unit = {
      require(condition, message)
      completed += 1
    }

    check(scalaSources(independentRoot) == Vector(independentSource.getCanonicalFile), "independent source inventory changed")
    check(scalaSources(consumerRoot) == Vector(consumerSource.getCanonicalFile), "consumer source inventory changed")
    check(scalaSources(bodyViewHandlerRoot) == Vector(bodyViewHandlerSource.getCanonicalFile), "body-view handler source inventory changed")
    check(scalaSources(bodyViewConsumerRoot) == Vector(bodyViewConsumerSource.getCanonicalFile), "body-view consumer source inventory changed")
    check(scalaSources(bodyViewNegativeRoot) == Vector(bodyViewNegativeSource.getCanonicalFile), "body-view negative source inventory changed")
    check(scalaSources(typePlacementHandlerRoot) == Vector(typePlacementHandlerSource.getCanonicalFile), "type-placement handler source inventory changed")
    check(scalaSources(typePlacementConsumerRoot) == Vector(typePlacementConsumerSource.getCanonicalFile), "type-placement consumer source inventory changed")
    check(scalaSources(typePlacementRejectRoot) == Vector(typePlacementRejectSource.getCanonicalFile), "type-placement reject source inventory changed")
    check(scalaSources(modulePlacementHandlerRoot) == Vector(modulePlacementHandlerSource.getCanonicalFile), "module-placement handler source inventory changed")
    check(scalaSources(modulePlacementConsumerRoot) == Vector(modulePlacementConsumerSource.getCanonicalFile), "module-placement consumer source inventory changed")
    check(scalaSources(modulePlacementRejectRoot) == Vector(modulePlacementRejectSource.getCanonicalFile), "module-placement reject source inventory changed")
    check(scalaSources(selfTraitHandlerRoot) == Vector(selfTraitHandlerSource.getCanonicalFile), "self-trait handler source inventory changed")
    check(scalaSources(selfTraitConsumerRoot) == Vector(selfTraitConsumerSource.getCanonicalFile), "self-trait consumer source inventory changed")
    check(scalaSources(selfTraitRejectRoot) == Vector(selfTraitRejectSource.getCanonicalFile), "self-trait reject source inventory changed")
    check(independent.contains("package contractprobe"), "independent package changed")
    check(independent.contains("final class IndependentMarker"), "independent marker class changed")
    check(independent.contains("final class IndependentHandler extends ParadiseAnnotationExpander"), "independent handler parent changed")
    check(independent.contains("@expander(\"contractprobe.IndependentHandler\")"), "independent marker metadata changed")
    check(independent.contains("val annotationName: String = \"IndependentMarker\""), "production annotation-name identity changed")
    check(independent.contains("ExpansionHelpers.withAnnotatedClassView(input)"), "structured class view is not used")
    check(independent.contains("ExpansionHelpers.addStringMethodToClass("), "bounded string-method helper is not used")
    check(independent.contains("methodName = \"independentHandlerName\""), "generated method name changed")
    check(!independent.contains("macroparadise."), "independent source imports plugin implementation")
    check(!independent.contains("paradise3.external"), "independent source imports repository markers")
    check(consumer.contains("import contractprobe.IndependentMarker"), "consumer does not import the independent marker")
    check(consumer.contains("@IndependentMarker"), "consumer is not ordinarily annotated")
    check(consumer.contains("final class IndependentConsumerUser"), "consumer class changed")
    check(consumer.contains("object IndependentPackagedConsumer"), "consumer entrypoint changed")
    check(consumer.contains("new IndependentConsumerUser().independentHandlerName"), "consumer does not typecheck the generated method")
    check(!consumer.contains("paradise3."), "consumer imports repository fixtures")
    check(bodyViewHandler.contains("package contractprobebody"), "body-view handler package changed")
    check(bodyViewHandler.contains("final class IndependentBodyViewMarker"), "body-view marker class changed")
    check(bodyViewHandler.contains("final class IndependentBodyViewHandler extends ParadiseAnnotationExpander"), "body-view handler parent changed")
    check(bodyViewHandler.contains("@expander(\"contractprobebody.IndependentBodyViewHandler\")"), "body-view marker metadata changed")
    check(bodyViewHandler.contains("val annotationName: String = \"IndependentBodyViewMarker\""), "body-view annotation identity changed")
    check(bodyViewHandler.contains("ExpansionTargetProfile.RestrictedGenericTraitApply"), "body-view handler does not request restricted trait admission")
    check(bodyViewHandler.contains("input.annotatedClassBodyView"), "bounded direct-body view is not used")
    check(bodyViewHandler.contains("DirectMemberKind.Method"), "bounded direct-member kind is not used")
    check(bodyViewHandler.contains("DirectTypeShape.EnclosingTypeParameter"), "bounded normalized type shape is not used")
    check(!bodyViewHandler.contains("untpd."), "body-view handler raw-matches untyped body trees")
    check(bodyViewHandler.contains("ExpansionHelpers.addStringMethodToCompanion("), "bounded companion method helper is not used")
    check(bodyViewConsumer.contains("trait IndependentMonoid[A]"), "body-view consumer lacks representative trait")
    check(bodyViewConsumer.contains("def empty: A"), "body-view consumer lacks no-clause abstract method")
    check(bodyViewConsumer.contains("def combine(a: A, a1: A): A"), "body-view consumer lacks ordered ordinary method")
    check(bodyViewConsumer.contains("IndependentMonoid.independentBodyView"), "body-view consumer does not use generated companion output")
    check(!bodyViewConsumer.contains("paradise3."), "body-view consumer imports repository API")
    check(bodyViewNegative.contains("@IndependentBodyViewMarker"), "body-view negative is not ordinarily annotated")
    check(bodyViewNegative.contains("def empty: A"), "body-view negative does not retain the valid no-clause method")
    check(bodyViewNegative.contains("def combine(a: List[A], a1: A): A"), "body-view negative does not isolate the applied parameter type")
    check(bodyViewNegative.contains("List[A]"), "body-view negative lacks an unsupported applied type")
    check(typePlacementHandler.contains("package contractprobetype"), "type-placement handler package changed")
    check(typePlacementHandler.contains("ExpansionTargetProfile.TwoUpperBoundedGenericTrait"), "type-placement handler does not request two-bounded-trait admission")
    check(typePlacementHandler.contains("override val consumesExistingCompanion: Boolean = true"), "type-placement handler does not lease existing companions")
    check(typePlacementHandler.contains("untpd.TypeDef("), "type-placement fixture does not create an already-lowered TypeDef")
    check(typePlacementHandler.contains("untpd.LambdaTypeTree("), "type-placement fixture lacks a representative generic alias")
    check(typePlacementHandler.contains("untpd.RefinedTypeTree("), "type-placement fixture lacks a representative refinement")
    check(typePlacementHandler.contains("ExpansionHelpers.addTypeToCompanion("), "type-placement helper is not used")
    check(typePlacementHandler.contains("CompanionTypeConflictPolicy.PreserveExisting"), "type-placement preserve policy is not exercised")
    check(typePlacementHandler.contains("CompanionTypeConflictPolicy.Reject"), "type-placement reject policy is not exercised")
    check(!typePlacementHandler.contains("macroparadise."), "type-placement source imports plugin implementation")
    check(!typePlacementHandler.contains("quasiquotes"), "type-placement source depends on Quasiquotes")
    check(typePlacementConsumer.contains("trait MissingCompanionAdd[N <: Nat, M <: Nat]"), "type-placement consumer lacks missing-companion proof")
    check(typePlacementConsumer.contains("object ExistingCompanionAdd"), "type-placement consumer lacks existing-companion proof")
    check(typePlacementConsumer.contains("type Aux"), "type-placement consumer lacks preserve-conflict proof")
    check(typePlacementConsumer.contains("MissingCompanionAdd.Aux"), "type-placement consumer does not typecheck the generated alias")
    check(typePlacementConsumer.contains("ExistingCompanionAdd.existingValue"), "type-placement consumer does not retain existing companion content")
    check(!typePlacementConsumer.contains("paradise3."), "type-placement consumer imports repository API")
    check(typePlacementReject.contains("@IndependentTypePlacementRejectMarker"), "type-placement reject consumer lacks reject annotation")
    check(typePlacementReject.contains("type Aux"), "type-placement reject consumer lacks direct type conflict")
    check(modulePlacementHandler.contains("package contractprobemodule"), "module-placement handler package changed")
    check(modulePlacementHandler.contains("final class IndependentModulePlacementMarker"), "module-placement marker class changed")
    check(modulePlacementHandler.contains("final class IndependentModulePlacementHandler extends ParadiseAnnotationExpander"), "module-placement handler parent changed")
    check(modulePlacementHandler.contains("@expander(\"contractprobemodule.IndependentModulePlacementHandler\")"), "module-placement marker metadata changed")
    check(modulePlacementHandler.contains("override val consumesExistingCompanion: Boolean = true"), "module-placement handler does not lease existing companions")
    check(modulePlacementHandler.contains("untpd.ModuleDef("), "module-placement fixture does not create an already-lowered ModuleDef")
    check(modulePlacementHandler.contains("termName(\"syntax\")"), "module-placement fixture does not create the syntax term name")
    check(modulePlacementHandler.contains("untpd.ValDef("), "module-placement fixture lacks a non-empty opaque module body")
    check(modulePlacementHandler.contains("ExpansionHelpers.addModuleToCompanion("), "module-placement helper is not used")
    check(modulePlacementHandler.contains("CompanionModuleConflictPolicy.PreserveExisting"), "module-placement preserve policy is not exercised")
    check(modulePlacementHandler.contains("CompanionModuleConflictPolicy.Reject"), "module-placement reject policy is not exercised")
    check(!modulePlacementHandler.contains("macroparadise."), "module-placement source imports plugin implementation")
    check(!modulePlacementHandler.contains("quasiquotes"), "module-placement source depends on Quasiquotes")
    check(modulePlacementConsumer.contains("trait MissingModuleAdd[N <: Nat, M <: Nat]"), "module-placement consumer lacks missing-companion proof")
    check(modulePlacementConsumer.contains("object ExistingModuleAdd"), "module-placement consumer lacks existing-companion proof")
    check(modulePlacementConsumer.contains("val syntax: String = \"preserved\""), "module-placement consumer lacks direct term-conflict preservation")
    check(modulePlacementConsumer.contains("MissingModuleAdd.syntax.marker"), "module-placement consumer does not observe the generated nested module")
    check(modulePlacementConsumer.contains("ExistingModuleAdd.existingValue"), "module-placement consumer does not retain existing companion content")
    check(!modulePlacementConsumer.contains("paradise3."), "module-placement consumer imports repository API")
    check(modulePlacementReject.contains("@IndependentModulePlacementRejectMarker"), "module-placement reject consumer lacks reject annotation")
    check(modulePlacementReject.contains("def syntax: String"), "module-placement reject consumer lacks a direct term conflict")
    check(selfTraitHandler.contains("package contractprobeself"), "self-trait handler package changed")
    check(selfTraitHandler.contains("final class IndependentSelfTraitMarker"), "self-trait marker class changed")
    check(selfTraitHandler.contains("ExpansionTargetProfile.PlainZeroParameterTrait"), "self-trait handler does not request the plain zero-parameter profile")
    check(selfTraitHandler.contains("ExpansionHelpers.addPreparedSelfTypeToTrait(input)"), "self-trait helper is not used")
    check(selfTraitHandler.contains("untpd.SingletonTypeTree("), "self-trait generated member does not depend on the prepared alias")
    check(selfTraitHandler.contains("direct Self preflight invoked lowering callback"), "self-trait reject control cannot detect callback-before-preflight ordering")
    check(!selfTraitHandler.contains("macroparadise."), "self-trait source imports plugin implementation")
    check(!selfTraitHandler.contains("quasiquotes"), "self-trait source depends on Quasiquotes")
    check(selfTraitConsumer.contains("trait AnonymousNat"), "self-trait consumer lacks an anonymous-self positive")
    check(selfTraitConsumer.contains("trait ExistingNamedNat"), "self-trait consumer lacks an existing-named-self positive")
    check(selfTraitConsumer.contains("trait CollisionNat"), "self-trait consumer lacks a collision positive")
    check(selfTraitConsumer.contains("type Existing = String"), "self-trait consumer does not preserve an original body member")
    check(selfTraitConsumer.contains("val self: String"), "self-trait consumer lacks the first direct term collision")
    check(selfTraitConsumer.contains("def self$1: String"), "self-trait consumer lacks stable suffix sequencing")
    check(selfTraitConsumer.contains(".Self ="), "self-trait consumer does not typecheck the generated member")
    check(!selfTraitConsumer.contains("paradise3."), "self-trait consumer imports repository API")
    check(selfTraitReject.contains("@IndependentSelfTraitMarker"), "self-trait reject consumer lacks the ordinary marker")
    check(selfTraitReject.contains("type Self = String"), "self-trait reject consumer lacks a direct Self conflict")
    check(selfTraitReject.contains("class RejectSelfClass"), "self-trait reject consumer lacks a class structural negative")
    check(selfTraitReject.contains("object RejectSelfObject"), "self-trait reject consumer lacks an object structural negative")
    check(selfTraitReject.contains("enum RejectSelfEnum"), "self-trait reject consumer lacks an enum structural negative")
    check(
      IndependentPrecompiledHandlerPackagedConsumer.expectedCompiledEntries == Set(
        "contractprobe/IndependentHandler.class",
        "contractprobe/IndependentHandler.tasty",
        "contractprobe/IndependentMarker.class",
        "contractprobe/IndependentMarker.tasty"
      ),
      "thin compiled-entry allowlist changed"
    )
    check(
      IndependentPrecompiledHandlerPackagedConsumer.forbiddenClasspathFragments.contains("plugin-test-markers"),
      "repository marker classpath exclusion changed"
    )
    check(
      IndependentPrecompiledHandlerPackagedConsumer.expectedTypePlacementCompiledEntries.size == 8,
      "type-placement compiled-entry allowlist changed"
    )
    check(
      IndependentPrecompiledHandlerPackagedConsumer.expectedModulePlacementCompiledEntries.size == 8,
      "module-placement compiled-entry allowlist changed"
    )
    check(
      IndependentPrecompiledHandlerPackagedConsumer.expectedSelfTraitCompiledEntries.size == 4,
      "self-trait compiled-entry allowlist changed"
    )
    require(completed == CaseCount, s"focused model spec ran $completed/$CaseCount cases")
  }

  private def scalaSources(root: File): Vector[File] = {
    if (!root.isDirectory) Vector.empty
    else root.listFiles().toVector.filter(file => file.isFile && file.getName.endsWith(".scala")).map(_.getCanonicalFile).sortBy(_.getAbsolutePath)
  }

  private def read(file: File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
}
