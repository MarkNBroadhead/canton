// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol.messages

import cats.syntax.either._
import com.digitalasset.canton.crypto.HashPurpose
import com.digitalasset.canton.data.{CantonTimestamp, CantonTimestampSecond}
import com.digitalasset.canton.topology._
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.messages.SignedProtocolMessageContent.SignedMessageContentCast
import com.digitalasset.canton.protocol.v0
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.store.db.DbDeserializationException
import com.digitalasset.canton.time.PositiveSeconds
import com.digitalasset.canton.util.NoCopy
import com.digitalasset.canton.version.{
  HasMemoizedProtocolVersionedWrapperCompanion,
  HasProtoV0,
  HasProtocolVersionedWrapper,
  ProtocolVersion,
  VersionedMessage,
}
import com.digitalasset.canton.ProtoDeserializationError
import com.google.protobuf.ByteString
import slick.jdbc.{GetResult, GetTupleResult, SetParameter}

import scala.math.Ordering.Implicits._

sealed abstract case class CommitmentPeriod(
    fromExclusive: CantonTimestampSecond,
    periodLength: PositiveSeconds,
) extends PrettyPrinting
    with NoCopy {
  val toInclusive: CantonTimestampSecond = fromExclusive + periodLength

  def overlaps(other: CommitmentPeriod): Boolean = {
    fromExclusive < other.toInclusive && toInclusive > other.fromExclusive
  }

  override def pretty: Pretty[CommitmentPeriod] =
    prettyOfClass(
      param("fromExclusive", _.fromExclusive),
      param("toInclusive", _.toInclusive),
    )
}

object CommitmentPeriod {
  def apply(
      fromExclusive: CantonTimestamp,
      periodLength: PositiveSeconds,
      interval: PositiveSeconds,
  ): Either[String, CommitmentPeriod] = for {
    from <- CantonTimestampSecond.fromCantonTimestamp(fromExclusive)
    _ <- Either.cond(
      periodLength.unwrap >= interval.unwrap || from == CantonTimestampSecond.MinValue,
      (),
      s"The period must be at least as large as the interval or start at MinValue, but is $periodLength and the interval is $interval",
    )
    _ <- Either.cond(
      from.getEpochSecond % interval.unwrap.getSeconds == 0 || from == CantonTimestampSecond.MinValue,
      (),
      s"The commitment period must start at a commitment tick or at MinValue, but it starts on $from, and the tick interval is $interval",
    )
    toInclusive = from + periodLength
    _ <- Either.cond(
      toInclusive.getEpochSecond % interval.unwrap.getSeconds == 0,
      (),
      s"The commitment period must end at a commitment tick, but it ends on $toInclusive, and the tick interval is $interval",
    )
  } yield new CommitmentPeriod(
    fromExclusive = from,
    periodLength = periodLength,
  ) {}

  def apply(
      fromExclusive: CantonTimestamp,
      toInclusive: CantonTimestamp,
      interval: PositiveSeconds,
  ): Either[String, CommitmentPeriod] =
    PositiveSeconds
      .between(fromExclusive, toInclusive)
      .flatMap(CommitmentPeriod(fromExclusive, _, interval))

  def apply(
      fromExclusive: CantonTimestampSecond,
      toInclusive: CantonTimestampSecond,
  ): Either[String, CommitmentPeriod] =
    PositiveSeconds.between(fromExclusive, toInclusive).map(CommitmentPeriod(fromExclusive, _))

  def apply(fromExclusive: CantonTimestampSecond, periodLength: PositiveSeconds): CommitmentPeriod =
    new CommitmentPeriod(
      fromExclusive = fromExclusive,
      periodLength = periodLength,
    ) {}

  implicit val getCommitmentPeriod: GetResult[CommitmentPeriod] =
    new GetTupleResult[(CantonTimestampSecond, CantonTimestampSecond)](
      GetResult[CantonTimestampSecond],
      GetResult[CantonTimestampSecond],
    ).andThen { case (from, to) =>
      PositiveSeconds
        .between(from, to)
        .map(CommitmentPeriod(from, _))
        .valueOr(err => throw new DbDeserializationException(err))
    }

}

/** A commitment to the active contract set (ACS) that is shared between two participants on a given domain at a given time.
  *
  *  Given a commitment scheme to the ACS, the semantics are as follows: the sender declares that the shared ACS was exactly
  *  the one committed to, at every commitment tick during the specified period and as determined by the period's interval.
  *
  *  The interval is assumed to be a round number of seconds. The ticks then start at the Java EPOCH time, and are exactly `interval` apart.
  */
case class AcsCommitment private (
    domainId: DomainId,
    sender: ParticipantId,
    counterParticipant: ParticipantId,
    period: CommitmentPeriod,
    commitment: AcsCommitment.CommitmentType,
)(
    val representativeProtocolVersion: ProtocolVersion,
    override val deserializedFrom: Option[ByteString],
) extends HasProtocolVersionedWrapper[VersionedMessage[AcsCommitment]]
    with HasProtoV0[v0.AcsCommitment]
    with SignedProtocolMessageContent
    with NoCopy {

  override protected def toProtoVersioned: VersionedMessage[AcsCommitment] =
    AcsCommitment.toProtoVersioned(this)

  override protected def toProtoV0: v0.AcsCommitment = {
    v0.AcsCommitment(
      domainId = domainId.toProtoPrimitive,
      sendingParticipant = sender.toProtoPrimitive,
      counterParticipant = counterParticipant.toProtoPrimitive,
      fromExclusive = Some(period.fromExclusive.toProtoPrimitive),
      toInclusive = Some(period.toInclusive.toProtoPrimitive),
      commitment = AcsCommitment.commitmentTypeToProto(commitment),
    )
  }

  override protected[this] def toByteStringUnmemoized: ByteString =
    super[HasProtocolVersionedWrapper].toByteString

  protected[messages] def toProtoSomeSignedProtocolMessage
      : v0.SignedProtocolMessage.SomeSignedProtocolMessage =
    v0.SignedProtocolMessage.SomeSignedProtocolMessage.AcsCommitment(getCryptographicEvidence)

  override def hashPurpose: HashPurpose = HashPurpose.AcsCommitment

  override lazy val pretty: Pretty[AcsCommitment] = {
    prettyOfClass(
      param("domainId", _.domainId),
      param("sender", _.sender),
      param("counterParticipant", _.counterParticipant),
      param("period", _.period),
      param("commitment", _.commitment),
    )
  }
}

object AcsCommitment extends HasMemoizedProtocolVersionedWrapperCompanion[AcsCommitment] {
  override val name: String = "AcsCommitment"

  val supportedProtoVersions = SupportedProtoVersions(
    0 -> VersionedProtoConverter(
      ProtocolVersion.v2_0_0,
      supportedProtoVersionMemoized(v0.AcsCommitment)(fromProtoV0),
      _.toProtoV0.toByteString,
    )
  )

  private val protocolVersionRepresentative = protocolVersionRepresentativeFor(
    ProtocolVersion.v2_0_0_Todo_i8793
  )

  type CommitmentType = ByteString
  implicit val getResultCommitmentType: GetResult[CommitmentType] =
    DbStorage.Implicits.getResultByteString
  implicit val setCommitmentType: SetParameter[CommitmentType] =
    DbStorage.Implicits.setParameterByteString

  sealed trait AcsCommitmentError

  case class IllegalCommitmentPeriod(after: CantonTimestamp, beforeAndAt: CantonTimestamp)
      extends AcsCommitmentError {
    override val toString: String =
      s"Illegal commitment: the after timestamp $after must precede the beforeAndAt timestamp $beforeAndAt"
  }

  def commitmentTypeToProto(commitment: CommitmentType): ByteString = commitment
  def commitmentTypeFromByteString(bytes: ByteString): CommitmentType = bytes

  private[this] def apply(
      domainId: DomainId,
      sender: ParticipantId,
      timestamp: CantonTimestamp,
      commitment: CommitmentType,
  )(
      representativeProtocolVersion: ProtocolVersion,
      deserializedFrom: Option[ByteString],
  ): AcsCommitment =
    throw new UnsupportedOperationException("Use the create/tryCreate methods instead")

  def create(
      domainId: DomainId,
      sender: ParticipantId,
      counterParticipant: ParticipantId,
      period: CommitmentPeriod,
      commitment: CommitmentType,
      protocolVersion: ProtocolVersion,
  ): AcsCommitment =
    new AcsCommitment(domainId, sender, counterParticipant, period, commitment)(
      protocolVersion,
      None,
    )

  private def fromProtoV0(protoMsg: v0.AcsCommitment)(
      bytes: ByteString
  ): ParsingResult[AcsCommitment] = {
    for {
      domainId <- DomainId.fromProtoPrimitive(protoMsg.domainId, "AcsCommitment.domainId")
      sender <- ParticipantId.fromProtoPrimitive(
        protoMsg.sendingParticipant,
        "AcsCommitment.sender",
      )
      counterParticipant <- ParticipantId.fromProtoPrimitive(
        protoMsg.counterParticipant,
        "AcsCommitment.counterParticipant",
      )
      fromExclusive <- ProtoConverter
        .required("AcsCommitment.period.fromExclusive", protoMsg.fromExclusive)
        .flatMap(CantonTimestampSecond.fromProtoPrimitive)
      toInclusive <- ProtoConverter
        .required("AcsCommitment.period.toInclusive", protoMsg.toInclusive)
        .flatMap(CantonTimestampSecond.fromProtoPrimitive)

      periodLength <- PositiveSeconds
        .between(fromExclusive, toInclusive)
        .leftMap { _ =>
          ProtoDeserializationError.InvariantViolation(
            s"Illegal commitment period length: $fromExclusive, $toInclusive"
          )
        }

      period = CommitmentPeriod(fromExclusive, periodLength)
      cmt = protoMsg.commitment
      commitment = commitmentTypeFromByteString(cmt)
    } yield new AcsCommitment(domainId, sender, counterParticipant, period, commitment)(
      protocolVersionRepresentativeFor(0),
      Some(bytes),
    )
  }

  implicit val acsCommitmentCast: SignedMessageContentCast[AcsCommitment] = {
    case m: AcsCommitment => Some(m)
    case _ => None
  }

  def getAcsCommitmentResultReader(domainId: DomainId): GetResult[AcsCommitment] =
    new GetTupleResult[(ParticipantId, ParticipantId, CommitmentPeriod, CommitmentType)](
      GetResult[ParticipantId],
      GetResult[ParticipantId],
      GetResult[CommitmentPeriod],
      GetResult[CommitmentType],
    ).andThen { case (sender, counterParticipant, period, commitment) =>
      AcsCommitment(domainId, sender, counterParticipant, period, commitment)(
        protocolVersionRepresentative,
        None,
      )
    }

}
