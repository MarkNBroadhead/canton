// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol

import com.digitalasset.canton.data.ViewType.TransactionViewType
import com.digitalasset.canton.sequencing.protocol.OpenEnvelope

/** This package contains data structures used in the transaction protocol.
  * However, generic data structures, e.g. [[com.digitalasset.canton.data.MerkleTree]] etc. are
  * kept in [[com.digitalasset.canton.data]] package.
  */
package object messages {

  type TransferOutResult = TransferResult[TransferOutDomainId]
  val TransferOutResult: TransferResult.type = TransferResult

  type TransferInResult = TransferResult[TransferInDomainId]
  val TransferInResult: TransferResult.type = TransferResult

  type TransactionViewMessage = EncryptedViewMessage[TransactionViewType]

  type DefaultOpenEnvelope = OpenEnvelope[ProtocolMessage]

}
