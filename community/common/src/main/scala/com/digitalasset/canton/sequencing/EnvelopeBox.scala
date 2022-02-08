// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing

import cats.{Applicative, Traverse}
import com.digitalasset.canton.sequencing.protocol.{Envelope, SequencedEvent, SignedContent}
import com.digitalasset.canton.store.SequencedEventStore.{
  IgnoredSequencedEvent,
  OrdinarySequencedEvent,
  PossiblyIgnoredSequencedEvent,
}
import com.digitalasset.canton.tracing.Traced

/** Type class to manipulate envelopes inside their box.
  * Specializes [[cats.Traverse]] to [[protocol.Envelope]] arguments.
  */
trait EnvelopeBox[Box[+_]] {

  /** Make this private so that we don't arbitrarily change the contents of a
    * [[com.digitalasset.canton.sequencing.protocol.SequencedEvent]] that has its serialization
    * memoized as cryptographic evidence.
    */
  private[sequencing] def traverse[G[_], A <: Envelope[_], B <: Envelope[_]](boxedEnvelope: Box[A])(
      f: A => G[B]
  )(implicit G: Applicative[G]): G[Box[B]]

  /** We can compose a [[cats.Traverse]] with an [[EnvelopeBox]], but not several [[EnvelopeBox]]es due to the
    * restriction to [[protocol.Envelope]]s in the type arguments.
    */
  type ComposedBox[Outer[+_], +A] = Outer[Box[A]]

  def revCompose[OuterBox[+_]](implicit
      OuterBox: Traverse[OuterBox]
  ): EnvelopeBox[ComposedBox[OuterBox, +*]] =
    new EnvelopeBox[ComposedBox[OuterBox, +*]] {
      override private[sequencing] def traverse[G[_], A <: Envelope[_], B <: Envelope[_]](
          boxedEnvelope: OuterBox[Box[A]]
      )(f: A => G[B])(implicit G: Applicative[G]): G[OuterBox[Box[B]]] =
        OuterBox.traverse(boxedEnvelope)(innerBox => EnvelopeBox.this.traverse(innerBox)(f))
    }
}

object EnvelopeBox {

  def apply[Box[+_]](implicit Box: EnvelopeBox[Box]): EnvelopeBox[Box] = Box

  implicit val sequencedEventEnvelopeBox: EnvelopeBox[SequencedEvent] =
    new EnvelopeBox[SequencedEvent] {
      override private[sequencing] def traverse[G[_], A <: Envelope[_], B <: Envelope[_]](
          event: SequencedEvent[A]
      )(f: A => G[B])(implicit G: Applicative[G]): G[SequencedEvent[B]] =
        event.traverse(f)
    }

  // It would be nice if we could appeal to a generic composition theorem here,
  // but the `MemoizeEvidence` bound in `SignedContent` doesn't allow a generic `Traverse` instance.
  implicit val signedContentEnvelopeBox: EnvelopeBox[RawSignedContentEnvelopeBox] =
    new EnvelopeBox[RawSignedContentEnvelopeBox] {
      override private[sequencing] def traverse[G[_], Env1 <: Envelope[_], Env2 <: Envelope[_]](
          signedEvent: SignedContent[SequencedEvent[Env1]]
      )(f: Env1 => G[Env2])(implicit G: Applicative[G]): G[RawSignedContentEnvelopeBox[Env2]] =
        signedEvent.traverse(_.traverse(f))
    }

  implicit val unsignedEnvelopeBox: EnvelopeBox[UnsignedEnvelopeBox] = {
    type TracedSeqTraced[+A] = Traced[Seq[Traced[A]]]
    EnvelopeBox[SequencedEvent].revCompose(
      Traverse[Traced].compose[Seq].compose[Traced]: Traverse[TracedSeqTraced]
    )
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def traverseOrdinarySequencedEvent[G[_], A <: Envelope[_], B <: Envelope[_]](
      ordinaryEvent: OrdinarySequencedEvent[A]
  )(f: A => G[B])(implicit G: Applicative[G]): G[OrdinarySequencedEvent[B]] = {
    val oldSignedEvent = ordinaryEvent.signedEvent
    G.map(signedContentEnvelopeBox.traverse(ordinaryEvent.signedEvent)(f)) { newSignedEvent =>
      if (newSignedEvent eq oldSignedEvent) ordinaryEvent.asInstanceOf[OrdinarySequencedEvent[B]]
      else ordinaryEvent.copy(signedEvent = newSignedEvent)(ordinaryEvent.traceContext)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def traverseIgnoredSequencedEvent[G[_], A <: Envelope[_], B <: Envelope[_]](
      event: IgnoredSequencedEvent[A]
  )(f: A => G[B])(implicit G: Applicative[G]): G[IgnoredSequencedEvent[B]] =
    event.underlying match {
      case none @ None => G.pure(event.asInstanceOf[IgnoredSequencedEvent[B]])
      case Some(signedEvent) =>
        G.map(signedContentEnvelopeBox.traverse(signedEvent)(f)) { newSignedEvent =>
          if (newSignedEvent eq signedEvent) event.asInstanceOf[IgnoredSequencedEvent[B]]
          else event.copy(underlying = Some(newSignedEvent))(event.traceContext)
        }
    }

  implicit val ordinarySequencedEventEnvelopeBox: EnvelopeBox[OrdinarySequencedEvent] =
    new EnvelopeBox[OrdinarySequencedEvent] {
      override private[sequencing] def traverse[G[_], A <: Envelope[_], B <: Envelope[_]](
          ordinaryEvent: OrdinarySequencedEvent[A]
      )(f: A => G[B])(implicit G: Applicative[G]): G[OrdinarySequencedEvent[B]] =
        traverseOrdinarySequencedEvent(ordinaryEvent)(f)
    }

  implicit val ignoredSequencedEventEnvelopeBox: EnvelopeBox[IgnoredSequencedEvent] =
    new EnvelopeBox[IgnoredSequencedEvent] {
      override private[sequencing] def traverse[G[_], A <: Envelope[_], B <: Envelope[_]](
          ignoredEvent: IgnoredSequencedEvent[A]
      )(f: A => G[B])(implicit G: Applicative[G]): G[IgnoredSequencedEvent[B]] =
        traverseIgnoredSequencedEvent(ignoredEvent)(f)
    }

  implicit val possiblyIgnoredSequencedEventEnvelopeBox
      : EnvelopeBox[PossiblyIgnoredSequencedEvent] =
    new EnvelopeBox[PossiblyIgnoredSequencedEvent] {
      override private[sequencing] def traverse[G[_], A <: Envelope[_], B <: Envelope[_]](
          event: PossiblyIgnoredSequencedEvent[A]
      )(f: A => G[B])(implicit G: Applicative[G]): G[PossiblyIgnoredSequencedEvent[B]] =
        event match {
          case ignored @ IgnoredSequencedEvent(_, _, _) =>
            G.widen(traverseIgnoredSequencedEvent[G, A, B](ignored)(f))
          case ordinary @ OrdinarySequencedEvent(_) =>
            G.widen(traverseOrdinarySequencedEvent(ordinary)(f))
        }
    }

  private type TracedSeq[+A] = Traced[Seq[A]]
  implicit val ordinaryEnvelopeBox: EnvelopeBox[OrdinaryEnvelopeBox] =
    ordinarySequencedEventEnvelopeBox.revCompose(Traverse[Traced].compose[Seq]: Traverse[TracedSeq])

  implicit val possiblyIgnoredEnvelopeBox: EnvelopeBox[PossiblyIgnoredEnvelopeBox] =
    possiblyIgnoredSequencedEventEnvelopeBox.revCompose(
      Traverse[Traced].compose[Seq]: Traverse[TracedSeq]
    )
}
