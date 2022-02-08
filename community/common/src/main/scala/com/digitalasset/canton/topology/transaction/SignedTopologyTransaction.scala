// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.transaction

import cats.data.EitherT
import cats.syntax.either._
import com.digitalasset.canton.DomainId
import com.digitalasset.canton.ProtoDeserializationError._
import com.digitalasset.canton.crypto._
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.v0
import com.digitalasset.canton.protocol.version._
import com.digitalasset.canton.serialization.{MemoizedEvidence, ProtoConverter}
import com.digitalasset.canton.store.db.DbSerializationException
import com.digitalasset.canton.util.{HasProtoV0, HasVersionedWrapper}
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import slick.jdbc.{GetResult, PositionedParameters, SetParameter}

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.logging.pretty.PrettyInstances._
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult

/** A signed topology transaction
  *
  * Every topology transaction needs to be authorized by an appropriate key. This object represents such
  * an authorization, where there is a signature of a given key of the given topology transaction.
  *
  * Whether the key is eligible to authorize the topology transaction depends on the topology state
  */
case class SignedTopologyTransaction[+Op <: TopologyChangeOp](
    transaction: TopologyTransaction[Op],
    key: SigningPublicKey,
    signature: Signature,
)(val deserializedFrom: Option[ByteString] = None)
    extends HasVersionedWrapper[VersionedSignedTopologyTransaction]
    with HasProtoV0[v0.SignedTopologyTransaction]
    with MemoizedEvidence
    with Product
    with Serializable
    with PrettyPrinting {

  override protected def toByteStringUnmemoized(version: ProtocolVersion): ByteString =
    super[HasVersionedWrapper].toByteString(version)
  override protected def toProtoVersioned(
      version: ProtocolVersion
  ): VersionedSignedTopologyTransaction =
    VersionedSignedTopologyTransaction(VersionedSignedTopologyTransaction.Version.V0(toProtoV0))

  override protected def toProtoV0: v0.SignedTopologyTransaction =
    v0.SignedTopologyTransaction(
      transaction = transaction.getCryptographicEvidence,
      key = Some(key.toProtoV0),
      signature = Some(signature.toProtoV0),
    )

  def verifySignature(pureApi: CryptoPureApi): Either[SignatureCheckError, Unit] = {
    val hash = transaction.hashToSign(pureApi)
    pureApi.verifySignature(hash, key, signature)
  }

  override def pretty: Pretty[SignedTopologyTransaction.this.type] =
    prettyOfClass(unnamedParam(_.transaction), param("key", _.key))

  def uniquePath: UniquePath = transaction.element.uniquePath

  def operation: Op = transaction.op

  def restrictedToDomain: Option[DomainId] = transaction.element.mapping.restrictedToDomain

  def reverse: SignedTopologyTransaction[TopologyChangeOp] =
    this.copy(transaction = transaction.reverse)(None)
}

object SignedTopologyTransaction {
  import com.digitalasset.canton.resource.DbStorage.Implicits._

  /** Sign the given topology transaction. */
  def create[Op <: TopologyChangeOp](
      transaction: TopologyTransaction[Op],
      signingKey: SigningPublicKey,
      hashOps: HashOps,
      crypto: CryptoPrivateApi,
  )(implicit ec: ExecutionContext): EitherT[Future, SigningError, SignedTopologyTransaction[Op]] =
    for {
      signature <- crypto.sign(transaction.hashToSign(hashOps), signingKey.id)
    } yield SignedTopologyTransaction(transaction, signingKey, signature)(None)

  private def fromProtoV0(
      transactionP: v0.SignedTopologyTransaction,
      bytes: ByteString,
  ): ParsingResult[SignedTopologyTransaction[TopologyChangeOp]] =
    for {
      transaction <- TopologyTransaction.fromByteString(transactionP.transaction)
      publicKey <- ProtoConverter.parseRequired(
        SigningPublicKey.fromProtoV0,
        "key",
        transactionP.key,
      )
      signature <- ProtoConverter.parseRequired(
        Signature.fromProtoV0,
        "signature",
        transactionP.signature,
      )
    } yield SignedTopologyTransaction(transaction, publicKey, signature)(Some(bytes))

  private def fromProtoVersioned(
      transactionP: VersionedSignedTopologyTransaction,
      bytes: ByteString,
  ): ParsingResult[SignedTopologyTransaction[TopologyChangeOp]] =
    transactionP.version match {
      case VersionedSignedTopologyTransaction.Version.Empty =>
        Left(FieldNotSet("VersionedSignedTopologyTransaction.version"))
      case VersionedSignedTopologyTransaction.Version.V0(transaction) =>
        fromProtoV0(transaction, bytes)
    }

  def fromByteString(
      bytes: ByteString
  ): ParsingResult[SignedTopologyTransaction[TopologyChangeOp]] =
    ProtoConverter
      .protoParser(VersionedSignedTopologyTransaction.parseFrom)(bytes)
      .flatMap(fromProtoVersioned(_, bytes))

  /** returns true if two transactions are equivalent */
  def equivalent(
      first: SignedTopologyTransaction[TopologyChangeOp],
      second: SignedTopologyTransaction[TopologyChangeOp],
  ): Boolean =
    (first, second) match {
      case (
            SignedTopologyTransaction(
              TopologyStateUpdate(firstOp, TopologyStateUpdateElement(_id, firstMapping)),
              firstKey,
              _,
            ),
            SignedTopologyTransaction(
              TopologyStateUpdate(secondOp, TopologyStateUpdateElement(_id2, secondMapping)),
              secondKey,
              _,
            ),
          ) =>
        firstOp == secondOp && firstKey == secondKey && firstMapping == secondMapping

      case (
            SignedTopologyTransaction(
              DomainGovernanceTransaction(DomainGovernanceElement(firstMapping)),
              firstKey,
              _,
            ),
            SignedTopologyTransaction(
              DomainGovernanceTransaction(DomainGovernanceElement(secondMapping)),
              secondKey,
              _,
            ),
          ) =>
        firstKey == secondKey && firstMapping == secondMapping

      case (
            SignedTopologyTransaction(_: TopologyStateUpdate[_], _, _),
            SignedTopologyTransaction(_: DomainGovernanceTransaction, _, _),
          ) =>
        false

      case (
            SignedTopologyTransaction(_: DomainGovernanceTransaction, _, _),
            SignedTopologyTransaction(_: TopologyStateUpdate[_], _, _),
          ) =>
        false
    }

  def createGetResultDomainTopologyTransaction
      : GetResult[SignedTopologyTransaction[TopologyChangeOp]] =
    GetResult { r =>
      fromByteString(r.<<)
        .valueOr(err =>
          throw new DbSerializationException(s"Failed to deserialize TopologyTransaction: $err")
        )
    }

  implicit def setParameterTopologyTransaction(implicit
      setParameterByteArray: SetParameter[Array[Byte]]
  ): SetParameter[SignedTopologyTransaction[TopologyChangeOp]] = {
    (d: SignedTopologyTransaction[TopologyChangeOp], pp: PositionedParameters) =>
      pp >> d.toByteArray(ProtocolVersion.default)
  }
}
