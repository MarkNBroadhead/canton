// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.admin.grpc

import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.participant.admin.DomainConnectivityService
import com.digitalasset.canton.participant.admin.v0._
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

class GrpcDomainConnectivityService(service: DomainConnectivityService)(implicit
    ec: ExecutionContext
) extends DomainConnectivityServiceGrpc.DomainConnectivityService {

  override def connectDomain(request: ConnectDomainRequest): Future[ConnectDomainResponse] =
    TraceContext.fromGrpcContext { implicit traceContext =>
      service.connectDomain(request.domainAlias, request.retry)
    }

  override def disconnectDomain(
      request: DisconnectDomainRequest
  ): Future[DisconnectDomainResponse] =
    TraceContext.fromGrpcContext { implicit traceContext =>
      service.disconnectDomain(request.domainAlias)
    }

  override def listConnectedDomains(
      request: ListConnectedDomainsRequest
  ): Future[ListConnectedDomainsResponse] =
    service.listConnectedDomains()

  override def listConfiguredDomains(
      request: ListConfiguredDomainsRequest
  ): Future[ListConfiguredDomainsResponse] =
    service.listConfiguredDomains()

  private def nonEmptyProcess[T, E](valueO: Option[T], use: T => Future[E]): Future[E] =
    valueO match {
      case None =>
        Future.failed(
          Status.INVALID_ARGUMENT.withDescription("Empty request received").asRuntimeException()
        )
      case Some(value) => use(value)
    }

  override def registerDomain(request: RegisterDomainRequest): Future[RegisterDomainResponse] =
    TraceContext.fromGrpcContext { implicit traceContext =>
      nonEmptyProcess(request.add, service.registerDomain)
    }

  override def getAgreement(request: GetAgreementRequest): Future[GetAgreementResponse] =
    TraceContext.fromGrpcContext { implicit traceContext =>
      service.getAgreement(request.domainAlias)
    }

  override def acceptAgreement(request: AcceptAgreementRequest): Future[AcceptAgreementResponse] =
    TraceContext.fromGrpcContext { implicit traceContext =>
      service.acceptAgreement(request.domainAlias, request.agreementId)
    }

  /** reconfigure a domain connection
    */
  override def modifyDomain(request: ModifyDomainRequest): Future[ModifyDomainResponse] =
    TraceContext.fromGrpcContext { implicit traceContext =>
      nonEmptyProcess(request.modify, service.modifyDomain)
    }

  /** reconnect to domains
    */
  override def reconnectDomains(
      request: ReconnectDomainsRequest
  ): Future[ReconnectDomainsResponse] =
    service.reconnectDomains(request.ignoreFailures).map(_ => ReconnectDomainsResponse())

  /** Get the domain id of the given domain alias
    */
  override def getDomainId(request: GetDomainIdRequest): Future[GetDomainIdResponse] =
    TraceContext.fromGrpcContext { implicit traceContext =>
      service.getDomainId(request.domainAlias).map { domainId =>
        GetDomainIdResponse(domainId = domainId.toProtoPrimitive)
      }
    }
}
