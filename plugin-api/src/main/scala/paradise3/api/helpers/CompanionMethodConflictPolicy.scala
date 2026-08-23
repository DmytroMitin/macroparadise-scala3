package paradise3.api.helpers

/** Syntactic direct-member conflict behavior for companion method placement.
  *
  * This policy compares only raw direct companion-member names before typer.
  * It does not perform semantic overload resolution or support replacement.
  */
enum CompanionMethodConflictPolicy:
  /** Preserve the supplied existing companion unchanged when a direct name conflicts. */
  case PreserveExisting
  /** Reject the expansion atomically when a direct name conflicts. */
  case Reject
