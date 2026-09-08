package paradise3.api.helpers

import paradise3.api.{DefinitionPlacement, ExpansionTargetKind}

/** Explicit behavior when a companion placement helper has no current companion. */
enum MissingCompanionPolicy:
  case Reject
  case Create(kind: ExpansionTargetKind, placement: DefinitionPlacement)
