// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.health

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.DebuggingDirectives
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.config.{HealthConfig, ProcessingTimeout}
import com.digitalasset.canton.environment.Environment
import com.digitalasset.canton.lifecycle._
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.metrics.HealthMetrics
import com.digitalasset.canton.tracing.TraceContext
import com.google.common.annotations.VisibleForTesting

class HealthServer(
    check: HealthCheck,
    address: String,
    port: Port,
    protected override val timeouts: ProcessingTimeout,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit system: ActorSystem)
    extends FlagCloseableAsync
    with NamedLogging {

  private val binding = {
    import TraceContext.Implicits.Empty._
    timeouts.unbounded.await(s"Binding the health server")(
      Http().newServerAt(address, port.unwrap).bind(HealthServer.route(check))
    )
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    import TraceContext.Implicits.Empty._
    List[AsyncOrSyncCloseable](
      AsyncCloseable("binding", binding.unbind(), timeouts.shutdownNetwork.unwrap),
      SyncCloseable("check", Lifecycle.close(check)(logger)),
    )
  }
}

object HealthServer {
  def apply(
      config: HealthConfig,
      metrics: HealthMetrics,
      timeouts: ProcessingTimeout,
      loggerFactory: NamedLoggerFactory,
  )(environment: Environment)(implicit system: ActorSystem): HealthServer = {
    val check = HealthCheck(config.check, metrics, timeouts, loggerFactory)(environment)

    new HealthServer(check, config.server.address, config.server.port, timeouts, loggerFactory)
  }

  /** Routes for powering the health server.
    * Provides:
    *   GET /health => calls check and returns:
    *     200 if healthy
    *     500 if unhealthy
    *     500 if the check fails
    */
  @VisibleForTesting
  private[health] def route(check: HealthCheck): Route = {
    implicit val marshaller: ToResponseMarshaller[HealthCheckResult] =
      Marshaller.opaque {
        case Healthy =>
          HttpResponse(status = StatusCodes.OK, entity = HttpEntity("healthy"))
        case Unhealthy(message) =>
          HttpResponse(status = StatusCodes.InternalServerError, entity = HttpEntity(message))
      }

    get {
      path("health") {
        DebuggingDirectives.logRequest("health-request") {
          DebuggingDirectives.logRequestResult("health-request-response") {
            complete(TraceContext.withNewTraceContext(check.isHealthy(_)))
          }
        }
      }
    }
  }
}
