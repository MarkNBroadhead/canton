// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol

import cats.data.EitherT
import cats.syntax.either._
import cats.syntax.functor._
import com.digitalasset.canton.SequencerCounter
import com.digitalasset.canton.crypto.{DomainSnapshotSyncCryptoApi, DomainSyncCryptoClient}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.participant.RequestCounter
import com.digitalasset.canton.participant.protocol.RequestJournal.RequestState
import com.digitalasset.canton.participant.protocol.conflictdetection.ActivenessSet
import com.digitalasset.canton.participant.store.SyncDomainEphemeralState
import com.digitalasset.canton.protocol.messages.{
  MediatorResponse,
  ProtocolMessage,
  SignedProtocolMessage,
}
import com.digitalasset.canton.protocol.RequestId
import com.digitalasset.canton.sequencing.client.{
  SendAsyncClientError,
  SendCallback,
  SequencerClient,
}
import com.digitalasset.canton.sequencing.protocol.{Batch, Recipients}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{ErrorUtil, FutureUtil}
import com.digitalasset.canton.util.ShowUtil._
import io.functionmeta.functionFullName

import scala.concurrent.{ExecutionContext, Future}

/** Collects helper methods for message processing */
abstract class AbstractMessageProcessor(
    ephemeral: SyncDomainEphemeralState,
    crypto: DomainSyncCryptoClient,
    sequencerClient: SequencerClient,
)(implicit ec: ExecutionContext)
    extends NamedLogging
    with FlagCloseable {

  protected def terminateRequest(
      requestCounter: RequestCounter,
      requestSequencerCounter: SequencerCounter,
      requestTimestamp: CantonTimestamp,
      commitTime: CantonTimestamp,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      cleanCursorF <- ephemeral.requestJournal.terminate(
        requestCounter,
        requestTimestamp,
        RequestState.Confirmed,
        commitTime,
      )
    } yield {
      val tickedF = cleanCursorF.map { _ =>
        // Tick the record order publisher only after the clean request prehead has reached the request
        // so that the record order publisher always lags behind the clean request head.
        // Note that the record order publisher may advance beyond the clean request prehead
        // due to other events (e.g., time proofs) after the clean request prehead becoming clean.
        ephemeral.recordOrderPublisher.tick(requestSequencerCounter, requestTimestamp)
      }
      FutureUtil.doNotAwait(tickedF, s"clean cursor future for request $requestCounter")
    }

  /** A clean replay replays a request whose request counter is below the clean head in the request journal.
    * Since the replayed request is clean, its effects are not persisted.
    */
  protected def isCleanReplay(requestCounter: RequestCounter): Boolean =
    requestCounter < ephemeral.startingPoints.processing.nextRequestCounter

  protected def unlessCleanReplay(requestCounter: RequestCounter)(f: => Future[_]): Future[Unit] =
    if (isCleanReplay(requestCounter)) Future.unit else f.void

  protected def signResponse(ips: DomainSnapshotSyncCryptoApi, response: MediatorResponse)(implicit
      traceContext: TraceContext
  ): Future[SignedProtocolMessage[MediatorResponse]] =
    SignedProtocolMessage.tryCreate(response, ips, ips.pureCrypto)

  // Assumes that we are not closing (i.e., that this is synchronized with shutdown somewhere higher up the call stack)
  protected def sendResponses(
      requestId: RequestId,
      rc: RequestCounter,
      messages: Seq[(ProtocolMessage, Recipients)],
  )(implicit traceContext: TraceContext): EitherT[Future, SendAsyncClientError, Unit] = {
    if (messages.isEmpty) EitherT.pure[Future, SendAsyncClientError](())
    else {
      logger.trace(s"Request $rc: ProtocolProcessor scheduling the sending of responses")

      for {
        domainParameters <- EitherT.right(
          crypto.ips
            .awaitSnapshot(requestId.unwrap)
            .flatMap(_.findDynamicDomainParametersOrDefault())
        )
        maxSequencingTime = requestId.unwrap.add(domainParameters.participantResponseTimeout.unwrap)
        _ <- sequencerClient.sendAsync(
          Batch.of(messages: _*),
          maxSequencingTime = maxSequencingTime,
          callback = SendCallback.log(s"Response message for request [$rc]", logger),
        )
      } yield ()
    }
  }

  /** Immediately moves the request to Confirmed and
    * register a timeout handler at the decision time with the request tracker
    * to cover the case that the mediator does not send a mediator result.
    */
  protected def prepareForMediatorResultOfBadRequest(
      requestCounter: RequestCounter,
      sequencerCounter: SequencerCounter,
      timestamp: CantonTimestamp,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    crypto.ips
      .awaitSnapshot(timestamp)
      .flatMap(_.findDynamicDomainParametersOrDefault())
      .flatMap { domainParameters =>
        val decisionTime = domainParameters.decisionTimeFor(timestamp)

        def onTimeout: Future[Unit] = {
          logger.debug(s"Bad request $requestCounter: Timed out without a mediator result message.")
          performUnlessClosingF(functionFullName) {
            terminateRequest(requestCounter, sequencerCounter, timestamp, decisionTime)
          }.onShutdown {
            logger.info(s"Ignoring timeout of bad request $requestCounter due to shutdown")
          }
        }

        registerRequestWithTimeout(
          requestCounter,
          sequencerCounter,
          timestamp,
          decisionTime,
          onTimeout,
        )
      }
  }

  private def registerRequestWithTimeout(
      requestCounter: RequestCounter,
      sequencerCounter: SequencerCounter,
      timestamp: CantonTimestamp,
      decisionTime: CantonTimestamp,
      onTimeout: => Future[Unit],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      requestFutures <- ephemeral.requestTracker
        .addRequest(
          requestCounter,
          sequencerCounter,
          timestamp,
          timestamp,
          decisionTime,
          ActivenessSet.empty,
        )
        .valueOr(error =>
          ErrorUtil.internalError(new IllegalStateException(show"Request already exists: $error"))
        )
      _ <- unlessCleanReplay(requestCounter)(
        ephemeral.requestJournal.insert(requestCounter, timestamp)
      )
      _ <- requestFutures.activenessResult
      _ <- unlessCleanReplay(requestCounter)(
        ephemeral.requestJournal.transit(
          requestCounter,
          timestamp,
          RequestState.Pending,
          RequestState.Confirmed,
        )
      )
      _ = ephemeral.phase37Synchronizer.markConfirmed(requestCounter, RequestId(timestamp))
      _ = if (!isCleanReplay(requestCounter)) {
        val timeoutF =
          requestFutures.timeoutResult.flatMap { timeoutResult =>
            if (timeoutResult.timedOut) onTimeout else Future.unit
          }
        FutureUtil.doNotAwait(timeoutF, "Handling timeout failed")
      }
    } yield ()

  /** Transition the request to Clean without doing anything */
  protected def invalidRequest(
      requestCounter: RequestCounter,
      sequencerCounter: SequencerCounter,
      timestamp: CantonTimestamp,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    // Let the request immediately timeout (upon the next message) rather than explicitly adding an empty commit set
    // because we don't have a sequencer counter to associate the commit set with.
    val decisionTime = timestamp.immediateSuccessor
    registerRequestWithTimeout(
      requestCounter,
      sequencerCounter,
      timestamp,
      decisionTime,
      terminateRequest(requestCounter, sequencerCounter, timestamp, decisionTime),
    )
  }
}
