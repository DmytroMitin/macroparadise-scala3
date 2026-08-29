package paradise3.api.helpers

/** How the bounded trait-self helper obtained the alias exposed to caller lowering. */
enum SelfAliasOrigin:
  /** Preserve the exact usable named self already present on the input trait. */
  case ExistingNamed
  /** Install one deterministic fresh alias for the input trait's anonymous self. */
  case Generated
