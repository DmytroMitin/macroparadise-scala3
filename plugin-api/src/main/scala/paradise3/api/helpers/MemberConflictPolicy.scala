package paradise3.api.helpers

/** Syntactic direct-member conflict handling for generic placement helpers. */
enum MemberConflictPolicy:
  case Reject
  case PreserveExisting
