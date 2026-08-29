package contractprobeselfconsumer

import contractprobeself.IndependentSelfTraitMarker

@IndependentSelfTraitMarker
trait RejectSelfNat:
  type Self = String
  type Existing = String

@IndependentSelfTraitMarker
class RejectSelfClass

@IndependentSelfTraitMarker
object RejectSelfObject

@IndependentSelfTraitMarker
enum RejectSelfEnum:
  case Only
