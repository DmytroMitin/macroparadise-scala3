package paradise3.api.helpers

/** Syntactic direct type-namespace conflict behavior for companion type placement.
  *
  * This policy compares only raw direct companion `TypeDef` names before typer.
  * It does not perform semantic name resolution or support replacement.
  */
enum CompanionTypeConflictPolicy:
  /** Preserve the supplied existing companion unchanged when a direct type name conflicts. */
  case PreserveExisting
  /** Reject the expansion atomically when a direct type name conflicts. */
  case Reject
