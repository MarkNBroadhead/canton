// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.networking.grpc

import com.digitalasset.canton.error.DecodedRpcStatus
import com.digitalasset.canton.error.ErrorCodeUtils.errorCategoryFromString
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.sequencing.authentication.MemberAuthentication.{
  MissingToken,
  ParticipantDisabled,
}
import com.digitalasset.canton.sequencing.authentication.grpc.Constant
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status.Code._
import io.grpc.{Metadata, Status, StatusRuntimeException}

import scala.annotation.nowarn

sealed trait GrpcError {

  def request: String
  def serverName: String
  def status: Status
  def decodedRpcStatus: Option[DecodedRpcStatus]
  def optTrailers: Option[Metadata]
  def hint: String = ""

  protected def logFullCause: Boolean = true

  override def toString: String = {
    val trailersString = (optTrailers, decodedRpcStatus) match {
      case (_, Some(rpc)) =>
        "\n  " + (rpc.correlationId.toList.map(s => s"CorrelationId: $s") ++ rpc.retryIn
          .map(s => s"RetryIn: $s")
          .toList ++ Seq(s"Context: ${rpc.context}")).mkString("\n  ")
      case (Some(trailers), None) if !trailers.keys.isEmpty => s"\n  Trailers: $trailers"
      case _ => ""
    }

    val causes = GrpcError.collectCauses(Option(status.getCause))
    val causesString = if (causes.isEmpty) "" else causes.mkString("\n  Causes: ", "\n    ", "")

    s"""Request failed for $serverName.$hint
       |  ${getClass.getSimpleName}: ${status.getCode}/${status.getDescription}
       |  Request: $request""".stripMargin + trailersString + causesString
  }

  def log(logger: TracedLogger)(implicit traceContext: TraceContext): Unit =
    if (logFullCause)
      logger.warn(toString, status.getCause)
    else logger.warn(toString)

  def retry: Boolean = decodedRpcStatus.exists(_.isRetryable)
}

object GrpcError {

  def collectCauses(maybeThrowable: Option[Throwable]): Seq[String] =
    maybeThrowable match {
      case Some(t) => t.getMessage +: collectCauses(Option(t.getCause))
      case None => Seq.empty
    }

  /** The server has refused the request, because it is invalid.
    * The client should not have sent the request.
    * The server has not processed the request.
    * It does not make sense to retry.
    */
  case class GrpcClientError(
      request: String,
      serverName: String,
      status: Status,
      optTrailers: Option[Metadata],
      decodedRpcStatus: Option[DecodedRpcStatus],
  ) extends GrpcError {
    override def log(logger: TracedLogger)(implicit traceContext: TraceContext): Unit =
      logger.error(toString, status.getCause)
  }

  /** An internal error has occurred at the server.
    * The server may have partially processed the request.
    * It does not make sense to retry.
    */
  case class GrpcServerError(
      request: String,
      serverName: String,
      status: Status,
      optTrailers: Option[Metadata],
      decodedRpcStatus: Option[DecodedRpcStatus],
  ) extends GrpcError {
    override def log(logger: TracedLogger)(implicit traceContext: TraceContext): Unit =
      logger.error(toString, status.getCause)
  }

  def checkAuthenticationError(optTrailers: Option[Metadata], expectAny: Seq[String]): Boolean = {
    val optErrorCode = for {
      trailers <- optTrailers
      errorCode <- Option(trailers.get(Constant.AUTHENTICATION_ERROR_CODE))
    } yield errorCode
    expectAny.exists(optErrorCode.contains)
  }

  /** The server was unable to process the request.
    * The server has not processed the request.
    * It may or may not make sense to retry, depending on the specific situation.
    */
  case class GrpcRequestRefusedByServer(
      request: String,
      serverName: String,
      status: Status,
      optTrailers: Option[Metadata],
      decodedRpcStatus: Option[DecodedRpcStatus],
  ) extends GrpcError {

    lazy val isAuthenticationTokenMissing: Boolean =
      checkAuthenticationError(optTrailers, Seq(MissingToken.toString))

    override def log(logger: TracedLogger)(implicit traceContext: TraceContext): Unit =
      if (isAuthenticationTokenMissing) {
        // Logging INFO only, as this happens from time to time due to token expiration.
        // Warn would be more natural, but very hard to manage in tests.
        logger.info(toString, status.getCause)
      } else {
        logger.warn(toString, status.getCause)
      }
  }

  /** The client gave up waiting for a response.
    * The server may or may not process the request.
    * It may or may not make sense to retry, depending on the specific situation.
    */
  case class GrpcClientGaveUp(
      request: String,
      serverName: String,
      status: Status,
      optTrailers: Option[Metadata],
      decodedRpcStatus: Option[DecodedRpcStatus],
  ) extends GrpcError {

    lazy val isClientCancellation: Boolean = status.getCode == CANCELLED && status.getCause == null

    override def log(logger: TracedLogger)(implicit traceContext: TraceContext): Unit =
      if (isClientCancellation) {
        logger.info(toString, status.getCause)
      } else {
        logger.warn(toString, status.getCause)
      }
  }

  private def lastCause(throwable: Throwable): Throwable = {
    Option(throwable.getCause).fold(throwable)(lastCause)
  }

  /** The server or the service was unavailable.
    * The server has not processed the request.
    * It makes sense to retry.
    */
  case class GrpcServiceUnavailable(
      request: String,
      serverName: String,
      status: Status,
      optTrailers: Option[Metadata],
      decodedRpcStatus: Option[DecodedRpcStatus],
  ) extends GrpcError {
    override def logFullCause: Boolean = _logFullCause
    override def hint: String = _hint
    private lazy val (_retry, _hint, _logFullCause) = status.getCode match {
      case UNAVAILABLE =>
        Option(status.getCause).map(lastCause) match {
          case Some(_: javax.net.ssl.SSLException) =>
            (false, " Are you using the right TLS settings?", true)
          case Some(_: java.net.UnknownHostException) => (false, " Is the url correct?", true)
          case _ =>
            // Mentioning TLS again, because sometimes we don't get an SSLException despite an SSL problem.
            (true, " Is the server running? Are you using the right TLS settings?", false)
        }
      case UNIMPLEMENTED =>
        (true, " Is the server initialized or is the server incompatible?", true)
      case CANCELLED => (true, " Server seems to have crashed", true)
      case _ => (true, "", true)
    }

    override def retry: Boolean = _retry
  }

  @nowarn("msg=match may not be exhaustive")
  def apply(request: String, serverName: String, e: StatusRuntimeException): GrpcError = {
    val status = e.getStatus
    val optTrailers = Option(e.getTrailers)
    val rpcStatus = DecodedRpcStatus.fromStatusRuntimeException(e)

    status.getCode match {
      case INVALID_ARGUMENT | UNAUTHENTICATED
          if !checkAuthenticationError(
            optTrailers,
            Seq(MissingToken.toString, ParticipantDisabled.toString),
          ) =>
        GrpcClientError(request, serverName, status, optTrailers, rpcStatus)

      case FAILED_PRECONDITION | NOT_FOUND | OUT_OF_RANGE | RESOURCE_EXHAUSTED | ABORTED |
          PERMISSION_DENIED | UNAUTHENTICATED | ALREADY_EXISTS =>
        GrpcRequestRefusedByServer(request, serverName, status, optTrailers, rpcStatus)

      case DEADLINE_EXCEEDED | CANCELLED =>
        GrpcClientGaveUp(request, serverName, status, optTrailers, rpcStatus)

      case UNAVAILABLE if errorCategoryFromString(status.getDescription).nonEmpty =>
        GrpcClientError(request, serverName, status, optTrailers, rpcStatus)

      case UNAVAILABLE | UNIMPLEMENTED =>
        GrpcServiceUnavailable(request, serverName, status, optTrailers, rpcStatus)

      case INTERNAL | UNKNOWN | DATA_LOSS =>
        GrpcServerError(request, serverName, status, optTrailers, rpcStatus)

      case OK =>
        GrpcServerError(
          request,
          serverName,
          status,
          optTrailers,
          rpcStatus,
        ) // broken, as a call should never fail with status OK
    }
  }

}
