// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.environment

import com.digitalasset.canton.admin.api.client.data.CommunityCantonStatus
import com.digitalasset.canton.config.{CantonCommunityConfig, TestingConfigInternal}
import com.digitalasset.canton.console.{
  CantonHealthAdministration,
  CommunityCantonHealthAdministration,
  CommunityLocalDomainReference,
  CommunityRemoteDomainReference,
  ConsoleEnvironment,
  ConsoleGrpcAdminCommandRunner,
  ConsoleOutput,
  DomainReference,
  FeatureFlag,
  Help,
  LocalDomainReference,
  LocalInstanceReference,
  LocalParticipantReference,
  NodeReferences,
  StandardConsoleOutput,
}
import com.digitalasset.canton.domain.DomainNodeBootstrap
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNodeBootstrap
import com.digitalasset.canton.resource.{CommunityDbMigrationsFactory, DbMigrationsFactory}

class CommunityEnvironment(
    override val config: CantonCommunityConfig,
    override val testingConfig: TestingConfigInternal,
    override val loggerFactory: NamedLoggerFactory,
) extends Environment {

  override type Config = CantonCommunityConfig

  override protected val participantNodeFactory
      : ParticipantNodeBootstrap.Factory[Config#ParticipantConfigType] =
    ParticipantNodeBootstrap.CommunityParticipantFactory

  override protected val domainFactory: DomainNodeBootstrap.Factory[Config#DomainConfigType] =
    DomainNodeBootstrap.CommunityDomainFactory
  override type Console = CommunityConsoleEnvironment

  override def createConsole(
      consoleOutput: ConsoleOutput,
      createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner,
  ): CommunityConsoleEnvironment =
    new CommunityConsoleEnvironment(this, consoleOutput, createAdminCommandRunner)

  override protected lazy val migrationsFactory: DbMigrationsFactory =
    new CommunityDbMigrationsFactory(loggerFactory)

  override def isEnterprise: Boolean = false

}

object CommunityEnvironmentFactory extends EnvironmentFactory[CommunityEnvironment] {
  override def create(
      config: CantonCommunityConfig,
      loggerFactory: NamedLoggerFactory,
      testingConfigInternal: TestingConfigInternal,
  ): CommunityEnvironment =
    new CommunityEnvironment(config, testingConfigInternal, loggerFactory)
}

class CommunityConsoleEnvironment(
    val environment: CommunityEnvironment,
    val consoleOutput: ConsoleOutput = StandardConsoleOutput,
    protected val createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner =
      new ConsoleGrpcAdminCommandRunner(_),
) extends ConsoleEnvironment {
  override type Env = CommunityEnvironment
  override type DomainLocalRef = CommunityLocalDomainReference
  override type DomainRemoteRef = CommunityRemoteDomainReference
  override type Status = CommunityCantonStatus

  private lazy val health_ = new CommunityCantonHealthAdministration(this)

  @Help.Summary("Environment health inspection")
  @Help.Group("Health")
  override def health: CantonHealthAdministration[CommunityCantonStatus] =
    health_

  override def startupOrderPrecedence(instance: LocalInstanceReference): Int = instance match {
    case _: LocalDomainReference => 1
    case _: LocalParticipantReference => 2
    case _ => 3
  }

  override protected def createDomainReference(name: String): DomainLocalRef =
    new CommunityLocalDomainReference(this, name)

  override protected def createRemoteDomainReference(name: String): DomainRemoteRef =
    new CommunityRemoteDomainReference(this, name)

  override protected def domainsTopLevelValue(
      h: TopLevelValue.Partial,
      domains: NodeReferences[
        DomainReference,
        CommunityRemoteDomainReference,
        CommunityLocalDomainReference,
      ],
  ): TopLevelValue[
    NodeReferences[DomainReference, CommunityRemoteDomainReference, CommunityLocalDomainReference]
  ] =
    h(domains)

  override protected def localDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: CommunityLocalDomainReference,
  ): TopLevelValue[CommunityLocalDomainReference] =
    h(d)

  override protected def remoteDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: CommunityRemoteDomainReference,
  ): TopLevelValue[CommunityRemoteDomainReference] =
    h(d)

  override protected def localDomainHelpItems(
      scope: Set[FeatureFlag],
      localDomain: CommunityLocalDomainReference,
  ): Seq[Help.Item] =
    Help.getItems(localDomain, baseTopic = Seq("$domain"), scope = scope)

  override protected def remoteDomainHelpItems(
      scope: Set[FeatureFlag],
      remoteDomain: CommunityRemoteDomainReference,
  ): Seq[Help.Item] =
    Help.getItems(remoteDomain, baseTopic = Seq("$domain"), scope = scope)
}
