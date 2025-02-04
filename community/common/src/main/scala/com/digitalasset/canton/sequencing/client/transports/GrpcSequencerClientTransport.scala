// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.client.transports

import cats.data.EitherT
import cats.syntax.either._
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.api.v0
import com.digitalasset.canton.domain.api.v0.SequencerServiceGrpc.SequencerServiceStub
import com.digitalasset.canton.domain.api.v0.SequencerConnectServiceGrpc.SequencerConnectServiceStub
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.lifecycle.Lifecycle.CloseableChannel
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.metrics.SequencerClientMetrics
import com.digitalasset.canton.networking.grpc.GrpcError.{
  GrpcClientGaveUp,
  GrpcServerError,
  GrpcServiceUnavailable,
}
import com.digitalasset.canton.networking.grpc.{CantonGrpcUtil, GrpcError}
import com.digitalasset.canton.sequencing.SerializedEventHandler
import com.digitalasset.canton.sequencing.client.{
  SendAsyncClientError,
  SequencerSubscription,
  SubscriptionErrorRetryPolicy,
}
import com.digitalasset.canton.sequencing.handshake.HandshakeRequestError
import com.digitalasset.canton.sequencing.protocol._
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.EitherUtil
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Context.CancellableContext
import io.grpc.{Context, ManagedChannel}

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class GrpcSequencerClientTransport(
    channel: ManagedChannel,
    clientAuth: GrpcSequencerClientAuth,
    metrics: SequencerClientMetrics,
    val timeouts: ProcessingTimeout,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends SequencerClientTransport
    with NamedLogging {

  private val sequencerConnectServiceClient = new SequencerConnectServiceStub(channel)
  private val sequencerServiceClient = clientAuth(new SequencerServiceStub(channel))
  private val noLoggingShutdownErrorsLogPolicy: GrpcError => TracedLogger => TraceContext => Unit =
    err =>
      logger =>
        traceContext =>
          err match {
            case _: GrpcClientGaveUp | _: GrpcServerError | _: GrpcServiceUnavailable =>
              // avoid logging client errors that typically happen during shutdown (such as grpc context cancelled)
              performUnlessClosing("grpc-client-transport-log")(err.log(logger)(traceContext))(
                traceContext
              ).discard
            case _ => err.log(logger)(traceContext)
          }

  /** Attempt to obtain a handshake response from the sequencer.
    * Transports can indicate in the error if the error is transient and could be retried.
    */
  override def handshake(request: HandshakeRequest)(implicit
      traceContext: TraceContext
  ): EitherT[Future, HandshakeRequestError, HandshakeResponse] =
    for {
      responseP <- CantonGrpcUtil
        .sendGrpcRequest(sequencerConnectServiceClient, "sequencer")(
          _.handshake(request.toProtoV0),
          requestDescription = "handshake",
          logger = logger,
          retryPolicy =
            _ => false, // we'll let the sequencer client decide whether to retry based on the error we return
          timeout = timeouts.network.duration,
          logPolicy = err =>
            logger =>
              traceContext =>
                logger.debug(s"Failed to handshake with sequencer: $err")(traceContext),
        )
        .leftMap(err => HandshakeRequestError(err.toString, err.retry))
      response <- HandshakeResponse
        .fromProtoV0(responseP)
        // if deserialization failed it likely means we have a version conflict on the handshake itself
        .leftMap(err =>
          HandshakeRequestError(s"Deserialization of response failed: $err", retryable = false)
        )
        .toEitherT[Future]
    } yield response

  override def sendAsync(
      request: SubmissionRequest,
      timeout: Duration,
      protocolVersion: ProtocolVersion,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, SendAsyncClientError, Unit] =
    sendAsyncInternal(request, timeout, requiresAuthentication = true, protocolVersion)

  override def sendAsyncUnauthenticated(
      request: SubmissionRequest,
      timeout: Duration,
      protocolVersion: ProtocolVersion,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, SendAsyncClientError, Unit] =
    sendAsyncInternal(request, timeout, requiresAuthentication = false, protocolVersion)

  private def sendAsyncInternal(
      request: SubmissionRequest,
      timeout: Duration,
      requiresAuthentication: Boolean,
      protocolVersion: ProtocolVersion,
  )(implicit traceContext: TraceContext): EitherT[Future, SendAsyncClientError, Unit] = {
    def fromGrpcError(error: GrpcError): Either[SendAsyncClientError, Unit] = {
      val result = EitherUtil.condUnitE(
        !bubbleSendErrorPolicy(error),
        SendAsyncClientError.RequestFailed(s"Failed to make request to the server: $error"),
      )

      // log that we're swallowing the error
      result.foreach { _ =>
        logger.info(
          s"Send [${request.messageId}] returned an error however may still be possibility sequenced so we are ignoring the error: $error"
        )
      }

      result
    }

    def fromResponse(responseP: v0.SendAsyncResponse): Either[SendAsyncClientError, Unit] =
      for {
        response <- SendAsyncResponse
          .fromProtoV0(responseP)
          .leftMap[SendAsyncClientError](err =>
            SendAsyncClientError.RequestFailed(s"Failed to deserialize response: $err")
          )
        _ <- response.error.toLeft(()).leftMap(SendAsyncClientError.RequestRefused)
      } yield ()

    val response = CantonGrpcUtil
      .sendGrpcRequest(sequencerServiceClient, "sequencer")(
        stub =>
          (if (requiresAuthentication) stub.sendAsync _ else stub.sendAsyncUnauthenticated _)(
            request.toProtoV0(protocolVersion)
          ),
        requestDescription =
          s"send-async${if (!requiresAuthentication) "-unauthenticated" else ""}/${request.messageId}",
        timeout = timeout,
        logger = logger,
        logPolicy = noLoggingShutdownErrorsLogPolicy,
        // sends are at-most-once so we cannot retry when unavailable as we don't know if the request has been accepted
        retryPolicy = retryPolicy(retryOnUnavailable = false),
      )

    response.biflatMap(fromGrpcError(_).toEitherT, fromResponse(_).toEitherT)
  }

  override def subscriptionRetryPolicy: SubscriptionErrorRetryPolicy =
    new GrpcSubscriptionErrorRetryPolicy(loggerFactory)

  /** We receive grpc errors for a variety of reasons. The send operation is at-most-once and should only be bubbled up
    * and potentially retried if we are absolutely certain the request will never be sequenced.
    */
  private def bubbleSendErrorPolicy(error: GrpcError): Boolean =
    error match {
      // bad request refused by server
      case _: GrpcError.GrpcClientError => true
      // the request was rejected by the server as it wasn't in a state to accept it
      case _: GrpcError.GrpcRequestRefusedByServer => true
      // an internal error happened at the server, this could have been when constructing or sending the response
      // after accepting the request so we cannot safely bubble the error
      case _: GrpcError.GrpcServerError => false
      // the service is unavailable, but this could have been returned after a request was received
      case _: GrpcServiceUnavailable => false
      // there was a timeout meaning we don't know what happened with the request
      case _: GrpcError.GrpcClientGaveUp => false
    }

  /** Retry policy to retry once for authentication failures to allow re-authentication and optionally retry when unavailable. */
  private def retryPolicy(
      retryOnUnavailable: Boolean
  )(implicit traceContext: TraceContext): GrpcError => Boolean = {
    // we allow one retry if the failure was due to an auth token expiration
    // if it's not refresh upon the next call we shouldn't retry again
    val hasRetriedDueToTokenExpiration = new AtomicBoolean(false)

    error =>
      if (isClosing) false // don't even think about retrying if we're closing
      else
        error match {
          case requestRefused: GrpcError.GrpcRequestRefusedByServer
              if !hasRetriedDueToTokenExpiration
                .get() && requestRefused.isAuthenticationTokenMissing =>
            logger.info(
              "Retrying once to give the sequencer the opportunity to refresh the authentication token."
            )
            hasRetriedDueToTokenExpiration.set(true) // don't allow again
            true
          // Retrying to recover from transient failures, e.g.:
          // - network outages
          // - sequencer starting up during integration tests
          case _: GrpcServiceUnavailable => retryOnUnavailable
          // don't retry on anything else as the request may have been received and a subsequent send may cause duplicates
          case _ => false
        }
  }

  override def subscribe[E](
      subscriptionRequest: SubscriptionRequest,
      handler: SerializedEventHandler[E],
  )(implicit traceContext: TraceContext): SequencerSubscription[E] =
    subscribeInternal(subscriptionRequest, handler, requiresAuthentication = true)

  private def subscribeInternal[E](
      subscriptionRequest: SubscriptionRequest,
      handler: SerializedEventHandler[E],
      requiresAuthentication: Boolean,
  )(implicit traceContext: TraceContext): SequencerSubscription[E] = {
    // we intentionally don't use `Context.current()` as we don't want to inherit the
    // cancellation scope from upstream requests
    val context: CancellableContext = Context.ROOT.withCancellation()
    val subscription = GrpcSequencerSubscription(context, handler, metrics, timeouts, loggerFactory)

    context.run(() =>
      traceContext.intoGrpcContext {
        if (requiresAuthentication)
          sequencerServiceClient.subscribe(subscriptionRequest.toProtoV0, subscription.observer)
        else
          sequencerServiceClient.subscribeUnauthenticated(
            subscriptionRequest.toProtoV0,
            subscription.observer,
          )
      }
    )

    subscription
  }

  override def subscribeUnauthenticated[E](
      request: SubscriptionRequest,
      handler: SerializedEventHandler[E],
  )(implicit traceContext: TraceContext): SequencerSubscription[E] =
    subscribeInternal(request, handler, requiresAuthentication = false)

  override def acknowledge(member: Member, timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val requestP = AcknowledgeRequest(member, timestamp).toProtoV0
    val responseP = CantonGrpcUtil.sendGrpcRequest(sequencerServiceClient, "sequencer")(
      _.acknowledge(requestP),
      requestDescription = s"acknowledge/$timestamp",
      timeout = timeouts.network.duration,
      logger = logger,
      logPolicy = noLoggingShutdownErrorsLogPolicy,
      retryPolicy = retryPolicy(retryOnUnavailable = false),
    )

    logger.debug(s"Acknowledging timestamp: $timestamp")
    responseP.value map {
      case Left(error) =>
        logger.warn(s"Failed to send acknowledgement for $timestamp: $error")
      case Right(_) =>
        logger.debug(s"Acknowledged timestamp: $timestamp")
    }
  }

  override protected def onClosed(): Unit =
    Lifecycle.close(
      clientAuth,
      new CloseableChannel(channel, logger, "grpc-sequencer-transport"),
    )(logger)
}
