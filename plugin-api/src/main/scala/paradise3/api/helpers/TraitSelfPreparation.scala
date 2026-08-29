package paradise3.api.helpers

import dotty.tools.dotc.util.SrcPos

/** Normalized preparation exposed before caller-owned `Self` member lowering.
  *
  * This record deliberately contains no raw template/self tree and retains no
  * compiler `Context`.
  */
final case class TraitSelfPreparation(
    selfAliasName: String,
    selfAliasOrigin: SelfAliasOrigin,
    pos: SrcPos
)
