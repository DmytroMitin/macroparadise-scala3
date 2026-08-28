package contractprobemoduleconsumerreject

import contractprobemodule.IndependentModulePlacementRejectMarker

sealed trait Nat

@IndependentModulePlacementRejectMarker
trait RejectModuleConflict[N <: Nat, M <: Nat]

object RejectModuleConflict:
  def syntax: String = "existing"
