package roleawareprobe

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("roleawareprobe.IndependentRoleAwareHandler")
final class IndependentRoleAwareMarker extends StaticAnnotation

@expander("roleawareprobe.IndependentLegacyClassHandler")
final class LegacyClassMarker extends StaticAnnotation

@expander("roleawareprobe.IndependentLegacyTraitHandler")
final class LegacyTraitMarker extends StaticAnnotation

@expander("roleawareprobe.IndependentOmittingHandler")
final class OmitMarker extends StaticAnnotation

@expander("roleawareprobe.IndependentRetainingHandler")
final class RetainMarker extends StaticAnnotation
