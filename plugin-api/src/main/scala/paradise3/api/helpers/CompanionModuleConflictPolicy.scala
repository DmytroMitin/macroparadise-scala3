package paradise3.api.helpers

/** Syntactic direct term-namespace conflict behavior for companion module placement.
  *
  * This policy compares only raw direct companion term-member names before
  * typer. It does not perform semantic name resolution or support replacement.
  */
enum CompanionModuleConflictPolicy:
  /** Preserve the supplied existing companion unchanged when a direct term name conflicts. */
  case PreserveExisting
  /** Reject the expansion atomically when a direct term name conflicts. */
  case Reject
