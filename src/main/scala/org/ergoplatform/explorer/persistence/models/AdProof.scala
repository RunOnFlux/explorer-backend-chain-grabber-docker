package org.ergoplatform.explorer.persistence.models

import org.ergoplatform.explorer.{HexString, Id}

/** Entity representing `node_ad_proofs` table.
  */
final case class AdProof(
  headerId: Id,
  proofBytes: HexString,
  digest: HexString
)
