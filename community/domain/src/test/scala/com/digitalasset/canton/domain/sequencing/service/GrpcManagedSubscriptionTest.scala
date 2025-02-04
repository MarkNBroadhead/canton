// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.service

import akka.NotUsed
import cats.data.EitherT
import com.digitalasset.canton.crypto.provider.symbolic.SymbolicCrypto
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.api.v0
import com.digitalasset.canton.domain.sequencing.sequencer.errors.CreateSubscriptionError
import com.digitalasset.canton.sequencing.SequencerTestUtils.MockMessageContent
import com.digitalasset.canton.sequencing._
import com.digitalasset.canton.sequencing.client.SequencerSubscription
import com.digitalasset.canton.sequencing.protocol._
import com.digitalasset.canton.store.SequencedEventStore.OrdinarySequencedEvent
import com.digitalasset.canton.topology.{
  DefaultTestIdentities,
  DomainId,
  ParticipantId,
  UniqueIdentifier,
}
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import io.grpc.stub.ServerCallStreamObserver
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class GrpcManagedSubscriptionTest extends AnyWordSpec with BaseTest with HasExecutionContext {

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Null"))
  private class Env {
    val sequencerSubscription = mock[SequencerSubscription[NotUsed]]
    val domainId = DomainId(UniqueIdentifier.tryFromProtoPrimitive("da::default"))
    var handler: Option[SerializedEventHandler[NotUsed]] = None
    val member = ParticipantId(DefaultTestIdentities.uid)
    val observer = mock[ServerCallStreamObserver[v0.SubscriptionResponse]]
    var cancelCallback: Option[Runnable] = None

    when(observer.setOnCancelHandler(any[Runnable]))
      .thenAnswer[Runnable](handler => cancelCallback = Some(handler))

    def cancel(): Unit =
      cancelCallback.fold(fail("no cancel handler registered"))(_.run())

    def createSequencerSubscription(
        newHandler: SerializedEventHandler[NotUsed]
    ): EitherT[Future, CreateSubscriptionError, SequencerSubscription[NotUsed]] = {
      handler = Some(newHandler)
      EitherT.rightT[Future, CreateSubscriptionError](sequencerSubscription)
    }

    def deliver(): Unit = {
      val message = MockMessageContent.toByteString
      val event = SignedContent(
        Deliver.create(
          0L,
          CantonTimestamp.Epoch,
          domainId,
          Some(MessageId.tryCreate("test-deliver")),
          Batch(List(ClosedEnvelope(message, Recipients.cc(member)))),
        ),
        SymbolicCrypto.emptySignature,
        None,
      )
      handler.fold(fail("handler not registered"))(h =>
        Await.result(h(OrdinarySequencedEvent(event)(traceContext)), 5.seconds)
      )
    }

    def createManagedSubscription() =
      new GrpcManagedSubscription(
        createSequencerSubscription,
        observer,
        member,
        None,
        timeouts,
        loggerFactory,
      )
  }

  "GrpcManagedSubscription" should {
    "send received events" in new Env {
      createManagedSubscription()
      deliver()
      verify(observer).onNext(any[v0.SubscriptionResponse])
    }

    "if observer is cancelled then subscription is closed but no response is sent" in new Env {
      createManagedSubscription()
      cancel()
      verify(sequencerSubscription).close()
      verify(observer, never).onError(any[Throwable])
      verify(observer, never).onCompleted()
    }

    "if closed externally the observer is completed, the subscription is closed, but the closed callback is not called" in new Env {
      val subscription = createManagedSubscription()
      subscription.close()
      verify(sequencerSubscription).close()
      verify(observer).onCompleted()
    }
  }
}
