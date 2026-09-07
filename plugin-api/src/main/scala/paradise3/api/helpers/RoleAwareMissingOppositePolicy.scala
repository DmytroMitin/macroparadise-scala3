package paradise3.api.helpers

import paradise3.api.OppositePlacement

/** Explicit request when an object edit has no current class/trait opposite.
  * Descriptor capability and actual source topology remain plugin-validated.
  */
enum RoleAwareMissingOppositePolicy:
  case Reject
  case CreateClass(placement: OppositePlacement)
  case CreateTrait(placement: OppositePlacement)
