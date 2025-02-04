// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.protocol

import cats.Functor
import cats.data.EitherT
import cats.syntax.traverse._
import com.digitalasset.canton.crypto._
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.protocol.v0
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.serialization.{ProtoConverter, ProtocolVersionedMemoizedEvidence}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.{
  HasProtoV0,
  HasProtocolVersionedWrapper,
  HasVersionedMessageWithContextCompanion,
  ProtocolVersion,
  VersionedMessage,
}
import com.google.protobuf.ByteString

import scala.concurrent.{ExecutionContext, Future}

case class SignedContent[+A <: ProtocolVersionedMemoizedEvidence](
    content: A,
    signature: Signature,
    timestampOfSigningKey: Option[CantonTimestamp],
) extends HasProtocolVersionedWrapper[VersionedMessage[SignedContent[A]]]
    with HasProtoV0[v0.SignedContent] {
  override def toProtoVersioned: VersionedMessage[SignedContent[A]] =
    VersionedMessage(toProtoV0.toByteString, 0)

  /** We use [[com.digitalasset.canton.version.ProtocolVersion.v2_0_0]] here because only v0 is defined
    * for SignedContent. This can be revisited when this wrapper will evolve.
    */
  def representativeProtocolVersion: ProtocolVersion = ProtocolVersion.v2_0_0

  def getCryptographicEvidence: ByteString = content.getCryptographicEvidence

  override def toProtoV0: v0.SignedContent =
    v0.SignedContent(
      Some(content.getCryptographicEvidence),
      Some(signature.toProtoV0),
      timestampOfSigningKey.map(_.toProtoPrimitive),
    )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def traverse[F[_], B <: ProtocolVersionedMemoizedEvidence](
      f: A => F[B]
  )(implicit F: Functor[F]): F[SignedContent[B]] =
    F.map(f(content)) { newContent =>
      if (newContent eq content) this.asInstanceOf[SignedContent[B]]
      else this.copy(content = newContent)
    }
}

object SignedContent {
  val protoConvertedSequencedEventClosedEnvelope
      : HasVersionedMessageWithContextCompanion[SignedContent[
        SequencedEvent[ClosedEnvelope]
      ], ByteString => ParsingResult[SequencedEvent[ClosedEnvelope]]] =
    SignedContent.versionedProtoConverter[SequencedEvent[ClosedEnvelope]]("ClosedEnvelope")

  def create[Env <: Envelope[_]](
      cryptoApi: CryptoPureApi,
      cryptoPrivateApi: SyncCryptoApi,
      event: SequencedEvent[Env],
      timestampOfSigningKey: Option[CantonTimestamp],
  )(implicit
      traceContext: TraceContext,
      ec: ExecutionContext,
  ): EitherT[Future, SyncCryptoError, SignedContent[SequencedEvent[Env]]] = {
    // as deliverEvent implements MemoizedEvidence repeated calls to serialize will return the same bytes
    // so fine to call once for the hash here and then again when serializing to protobuf
    val hash = hashContent(cryptoApi, event)
    cryptoPrivateApi
      .sign(hash)
      .map(signature => SignedContent(event, signature, timestampOfSigningKey))
  }

  def hashContent(cryptoApi: CryptoPureApi, sequencedEvent: SequencedEvent[_]): Hash =
    cryptoApi.digest(HashPurpose.SequencedEventSignature, sequencedEvent.getCryptographicEvidence)

  def tryCreate[Env <: Envelope[_]](
      cryptoApi: CryptoPureApi,
      cryptoPrivateApi: SyncCryptoApi,
      event: SequencedEvent[Env],
      timestampOfSigningKey: Option[CantonTimestamp],
  )(implicit
      traceContext: TraceContext,
      ec: ExecutionContext,
  ): Future[SignedContent[SequencedEvent[Env]]] =
    create(cryptoApi, cryptoPrivateApi, event, timestampOfSigningKey)
      .fold(
        err => throw new IllegalStateException(s"Failed to create signed content: $err"),
        identity,
      )

  def versionedProtoConverter[A <: ProtocolVersionedMemoizedEvidence](
      contentType: String
  ): HasVersionedMessageWithContextCompanion[SignedContent[A], ByteString => ParsingResult[A]] =
    new HasVersionedMessageWithContextCompanion[SignedContent[A], ByteString => ParsingResult[A]] {
      override val name: String = s"SignedContent[$contentType]"

      val supportedProtoVersions: Map[Int, Parser] = Map(
        0 -> supportedProtoVersion(v0.SignedContent) { (deserializer, proto) =>
          fromProtoV0(deserializer)(proto)
        }
      )
    }

  def fromProtoV0[A <: ProtocolVersionedMemoizedEvidence](
      deserializer: ByteString => ParsingResult[A]
  )(signedValueP: v0.SignedContent): ParsingResult[SignedContent[A]] =
    signedValueP match {
      case v0.SignedContent(content, maybeSignatureP, timestampOfSigningKey) =>
        for {
          contentB <- ProtoConverter.required("content", content)
          content <- deserializer(contentB)
          signature <- ProtoConverter.parseRequired(
            Signature.fromProtoV0,
            "signature",
            maybeSignatureP,
          )
          ts <- timestampOfSigningKey.traverse(CantonTimestamp.fromProtoPrimitive)
        } yield SignedContent(content, signature, ts)
    }

  implicit def prettySignedContent[A <: ProtocolVersionedMemoizedEvidence](implicit
      prettyA: Pretty[A]
  ): Pretty[SignedContent[A]] = {
    import com.digitalasset.canton.logging.pretty.PrettyUtil._
    prettyOfClass(
      unnamedParam(_.content),
      param("signature", _.signature),
      paramIfDefined("timestamp of signing key", _.timestampOfSigningKey),
    )
  }
}
