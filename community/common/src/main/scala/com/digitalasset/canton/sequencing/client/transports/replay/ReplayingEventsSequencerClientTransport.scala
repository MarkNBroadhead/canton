// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.client.transports.replay

import cats.data.EitherT
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.metrics.SequencerClientMetrics
import com.digitalasset.canton.sequencing.client.SequencerClient.ReplayStatistics
import com.digitalasset.canton.sequencing.client._
import com.digitalasset.canton.sequencing.client.transports.SequencerClientTransport
import com.digitalasset.canton.sequencing.client.transports.replay.ReplayingEventsSequencerClientTransport.ReplayingSequencerSubscription
import com.digitalasset.canton.sequencing.handshake.HandshakeRequestError
import com.digitalasset.canton.sequencing.protocol.{
  HandshakeRequest,
  HandshakeResponse,
  SubmissionRequest,
  SubscriptionRequest,
}
import com.digitalasset.canton.sequencing.{SequencerClientRecorder, SerializedEventHandler}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil._
import com.digitalasset.canton.util.{ErrorUtil, FutureUtil, MonadUtil}
import com.digitalasset.canton.version.ProtocolVersion

import java.nio.file.Path
import java.time.{Instant, Duration => JDuration}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

/** Transport implementation for replaying messages from a file.
  * @param replayPath points to a file containing events to be replayed.
  *                   The events must be serialized versions of `TracedSignedSerializedSequencedEvent`.
  */
class ReplayingEventsSequencerClientTransport(
    protocolVersion: ProtocolVersion,
    replayPath: Path,
    metrics: SequencerClientMetrics,
    override protected val timeouts: ProcessingTimeout,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends SequencerClientTransport
    with NamedLogging {

  /** Does nothing. */
  override def sendAsync(
      request: SubmissionRequest,
      timeout: Duration,
      protocolVersion: ProtocolVersion,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, SendAsyncClientError, Unit] = EitherT.rightT(())

  /** Does nothing. */
  override def sendAsyncUnauthenticated(
      request: SubmissionRequest,
      timeout: Duration,
      protocolVersion: ProtocolVersion,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, SendAsyncClientError, Unit] = EitherT.rightT(())

  /** Does nothing */
  override def acknowledge(member: Member, timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Unit] = Future.unit

  /** Replays all events in `replayPath` to the handler. */
  override def subscribe[E](request: SubscriptionRequest, handler: SerializedEventHandler[E])(
      implicit traceContext: TraceContext
  ): ReplayingSequencerSubscription[E] = {
    logger.info("Loading messages for replaying...")
    val messages = ErrorUtil.withThrowableLogging {
      SequencerClientRecorder.loadEvents(replayPath, logger)
    }
    logger.info(s"Start feeding ${messages.size} messages to the subscription...")
    val startTime = CantonTimestamp.now()
    val replayF = MonadUtil
      .sequentialTraverse_(messages) { e =>
        logger.debug(
          s"Replaying event with sequencer counter ${e.counter} and timestamp ${e.timestamp}"
        )(e.traceContext)
        for {
          unitOrErr <- metrics.load.metric.event(handler(e))
        } yield unitOrErr match {
          case Left(err) =>
            logger.error(s"The sequencer handler returned an error: $err")
          case Right(()) =>
        }
      }
      .map { _ =>
        val duration = JDuration.between(startTime.toInstant, Instant.now)
        logger.info(
          show"Finished feeding ${messages.size} messages within $duration to the subscription."
        )
        SequencerClient.replayStatistics.add(
          ReplayStatistics(replayPath, messages.size, startTime, duration)
        )
      }

    FutureUtil.doNotAwait(replayF, "An exception has occurred while replaying messages.")
    new ReplayingSequencerSubscription(timeouts, loggerFactory)
  }

  override def subscribeUnauthenticated[E](
      request: SubscriptionRequest,
      handler: SerializedEventHandler[E],
  )(implicit traceContext: TraceContext): SequencerSubscription[E] = subscribe(request, handler)

  /** Will never request a retry. */
  override def subscriptionRetryPolicy: SubscriptionErrorRetryPolicy =
    SubscriptionErrorRetryPolicy.never

  /** Will always succeed. */
  override def handshake(request: HandshakeRequest)(implicit
      traceContext: TraceContext
  ): EitherT[Future, HandshakeRequestError, HandshakeResponse] =
    EitherT.rightT(HandshakeResponse.Success(protocolVersion))
}

object ReplayingEventsSequencerClientTransport {

  /** Does nothing until closed. */
  class ReplayingSequencerSubscription[E](
      override protected val timeouts: ProcessingTimeout,
      override protected val loggerFactory: NamedLoggerFactory,
  )(implicit val executionContext: ExecutionContext)
      extends SequencerSubscription[E]
}
