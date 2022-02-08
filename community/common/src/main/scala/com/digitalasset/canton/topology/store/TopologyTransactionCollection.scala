// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.store

import cats.syntax.functorFilter._
import cats.syntax.traverse._
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.v0
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.topology.processing.AuthorizedTopologyTransaction
import com.digitalasset.canton.topology.transaction.TopologyChangeOp.{
  Add,
  Positive,
  Remove,
  Replace,
}
import com.digitalasset.canton.topology.transaction._
import com.digitalasset.canton.util.HasProtoV0

final case class StoredTopologyTransactions[+Op <: TopologyChangeOp](
    result: Seq[StoredTopologyTransaction[Op]]
) extends HasProtoV0[v0.TopologyTransactions]
    with PrettyPrinting {

  override def pretty: Pretty[StoredTopologyTransactions.this.type] = prettyOfParam(
    _.result
  )

  def toIdentityState: List[TopologyStateElement[TopologyMapping]] =
    result.map(_.transaction.transaction.element).toList

  def toDomainTopologyTransactions: Seq[SignedTopologyTransaction[Op]] =
    result.map(_.transaction)

  override def toProtoV0: v0.TopologyTransactions = v0.TopologyTransactions(
    items = result.map { item =>
      v0.TopologyTransactions.Item(
        validFrom = Some(item.validFrom.toProtoPrimitive),
        validUntil = item.validUntil.map(_.toProtoPrimitive),
        // these transactions are serialized as versioned topology transactions
        transaction = item.transaction.getCryptographicEvidence,
      )
    }
  )

  def toAuthorizedTopologyTransactions[T <: TopologyMapping](
      collector: PartialFunction[TopologyMapping, T]
  ): Seq[AuthorizedTopologyTransaction[T]] = {

    val transactions: Seq[SignedTopologyTransaction[TopologyChangeOp]] = result.map(_.transaction)

    transactions.flatMap {
      case sit @ SignedTopologyTransaction(
            TopologyStateUpdate(Add, TopologyStateUpdateElement(_, mapping)),
            key,
            _,
          ) =>
        collector
          .lift(mapping)
          .map { matched =>
            AuthorizedTopologyTransaction(sit.uniquePath, matched, key.fingerprint)
          }
          .toList
      case _ => Seq()
    }
  }

  def collectOfType[T <: TopologyChangeOp](implicit
      checker: TopologyChangeOp.OpTypeChecker[T]
  ): StoredTopologyTransactions[T] = StoredTopologyTransactions(
    result.mapFilter(TopologyChangeOp.select[T])
  )

  def split: (
      StoredTopologyTransactions[Add],
      StoredTopologyTransactions[Remove],
      StoredTopologyTransactions[Replace],
  ) = {
    val (adds, removes, replaces) = TopologyTransactionSplitter[Op, StoredTopologyTransaction](
      collection = result,
      opProjector = _.transaction.operation,
      addSelector = TopologyChangeOp.select[Add](_),
      removeSelector = TopologyChangeOp.select[Remove](_),
      replaceSelector = TopologyChangeOp.select[Replace](_),
    )

    (
      StoredTopologyTransactions(adds),
      StoredTopologyTransactions(removes),
      StoredTopologyTransactions(replaces),
    )
  }

  def positiveTransactions: PositiveStoredTopologyTransactions = {
    val (adds, _, replaces) = split
    PositiveStoredTopologyTransactions(adds, replaces)
  }

  /** Split transactions into certificates and everything else (used when uploading to a participant) */
  def splitCertsAndRest: StoredTopologyTransactions.CertsAndRest[Op] = {
    val certTypes = Set(
      DomainTopologyTransactionType.IdentifierDelegation,
      DomainTopologyTransactionType.NamespaceDelegation,
    )
    val empty = Seq.empty[StoredTopologyTransaction[Op]]
    val (certs, rest) = result.foldLeft((empty, empty)) { case ((certs, rest), tx) =>
      if (certTypes.contains(tx.transaction.uniquePath.dbType))
        (certs :+ tx, rest)
      else
        (certs, rest :+ tx)
    }
    StoredTopologyTransactions.CertsAndRest(certs, rest)
  }

}

object StoredTopologyTransactions {

  case class CertsAndRest[+Op <: TopologyChangeOp](
      certs: Seq[StoredTopologyTransaction[Op]],
      rest: Seq[StoredTopologyTransaction[Op]],
  )

  def fromProtoV0(
      value: v0.TopologyTransactions
  ): ParsingResult[StoredTopologyTransactions[TopologyChangeOp]] = {
    def parseItem(
        item: v0.TopologyTransactions.Item
    ): ParsingResult[StoredTopologyTransaction[TopologyChangeOp]] = {
      for {
        validFromP <- ProtoConverter.required("valid_from", item.validFrom)
        validFrom <- CantonTimestamp.fromProtoPrimitive(validFromP)
        validUntil <- item.validFrom.traverse(CantonTimestamp.fromProtoPrimitive)
        transaction <- SignedTopologyTransaction.fromByteString(item.transaction)
      } yield StoredTopologyTransaction(validFrom, validUntil, transaction)
    }
    value.items
      .traverse(parseItem)
      .map(StoredTopologyTransactions(_))
  }

  def empty[Op <: TopologyChangeOp]: StoredTopologyTransactions[Op] =
    StoredTopologyTransactions(Seq())
}

final case class PositiveStoredTopologyTransactions(
    adds: StoredTopologyTransactions[Add],
    replaces: StoredTopologyTransactions[Replace],
) {
  def toIdentityState: List[TopologyStateElement[TopologyMapping]] =
    adds.toIdentityState ++ replaces.toIdentityState

  def combine: StoredTopologyTransactions[Positive] = StoredTopologyTransactions(
    adds.result ++ replaces.result
  )

  def signedTransactions = PositiveSignedTopologyTransactions(
    SignedTopologyTransactions(adds.toDomainTopologyTransactions),
    SignedTopologyTransactions(replaces.toDomainTopologyTransactions),
  )
}

final case class SignedTopologyTransactions[+Op <: TopologyChangeOp](
    result: Seq[SignedTopologyTransaction[Op]]
) {
  def collectOfType[T <: TopologyChangeOp](implicit
      checker: TopologyChangeOp.OpTypeChecker[T]
  ): SignedTopologyTransactions[T] = SignedTopologyTransactions(
    result.mapFilter(TopologyChangeOp.select[T])
  )

  def split: (
      SignedTopologyTransactions[Add],
      SignedTopologyTransactions[Remove],
      SignedTopologyTransactions[Replace],
  ) = {
    val (adds, removes, replaces) = TopologyTransactionSplitter[Op, SignedTopologyTransaction](
      collection = result,
      opProjector = _.operation,
      addSelector = TopologyChangeOp.select[Add](_),
      removeSelector = TopologyChangeOp.select[Remove](_),
      replaceSelector = TopologyChangeOp.select[Replace](_),
    )

    (
      SignedTopologyTransactions(adds),
      SignedTopologyTransactions(removes),
      SignedTopologyTransactions(replaces),
    )
  }

  def filter(predicate: SignedTopologyTransaction[Op] => Boolean): SignedTopologyTransactions[Op] =
    this.copy(result = result.filter(predicate))
}

final case class PositiveSignedTopologyTransactions(
    adds: SignedTopologyTransactions[Add],
    replaces: SignedTopologyTransactions[Replace],
) {
  def filter(
      predicate: SignedTopologyTransaction[Positive] => Boolean
  ): PositiveSignedTopologyTransactions =
    this.copy(adds = adds.filter(predicate), replaces = replaces.filter(predicate))
}

object TopologyTransactionSplitter {
  import TopologyChangeOp._

  def apply[Op <: TopologyChangeOp, F[_ <: TopologyChangeOp]](
      collection: Seq[F[TopologyChangeOp]],
      opProjector: F[TopologyChangeOp] => TopologyChangeOp,
      addSelector: F[TopologyChangeOp] => Option[F[Add]],
      removeSelector: F[TopologyChangeOp] => Option[F[Remove]],
      replaceSelector: F[TopologyChangeOp] => Option[F[Replace]],
  ): (Seq[F[Add]], Seq[F[Remove]], Seq[F[Replace]]) = {
    val zero = (Seq.empty[F[Add]], Seq.empty[F[Remove]], Seq.empty[F[Replace]])

    collection.foldLeft(zero) { case ((adds, removes, replaces), element) =>
      opProjector(element) match {
        case Add => (adds ++ addSelector(element), removes, replaces)
        case Remove => (adds, removes ++ removeSelector(element), replaces)
        case Replace => (adds, removes, replaces ++ replaceSelector(element))
      }
    }
  }
}
