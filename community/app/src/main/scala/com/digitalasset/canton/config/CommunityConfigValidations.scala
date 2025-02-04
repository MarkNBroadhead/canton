// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.config

import cats.data.Validated
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import com.daml.nonempty.NonEmpty
import com.daml.nonempty.catsinstances._
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.HandshakeErrors.UnsafePvVersion2_0_0
import com.digitalasset.canton.version.ProtocolVersion

private[config] trait ConfigValidations[C <: CantonConfig] {
  final def validate(config: C): Validated[NonEmpty[Seq[String]], Unit] =
    validations.traverse_(_(config))

  protected val validations: List[C => Validated[NonEmpty[Seq[String]], Unit]]
}

object CommunityConfigValidations
    extends ConfigValidations[CantonCommunityConfig]
    with NamedLogging {
  import TraceContext.Implicits.Empty._
  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  case class DbAccess(url: String, user: Option[String])

  private val Valid: Validated[NonEmpty[Seq[String]], Unit] = Validated.valid(())
  type Validation = CantonCommunityConfig => Validated[NonEmpty[Seq[String]], Unit]

  override protected val validations: List[Validation] =
    List[Validation](noDuplicateStorage, atLeastOneNode) ++ genericValidations[
      CantonCommunityConfig
    ]

  /** Validations applied to all community and enterprise Canton configurations. */
  private[config] def genericValidations[C <: CantonConfig]
      : List[C => Validated[NonEmpty[Seq[String]], Unit]] =
    List(
      backwardsCompatibleLoggingConfig,
      developmentProtocolSafetyCheckDomains,
      warnIfUnsafeMinProtocolVersion,
      warnIfUnsafeProtocolVersionEmbeddedDomain,
    )

  /** Group node configs by db access to find matching db storage configs.
    * Overcomplicated types used are to work around that at this point nodes could have conflicting names so we can't just
    * throw them all in a single map.
    */
  private[config] def extractNormalizedDbAccess[C <: CantonConfig](
      nodeConfigs: Map[String, LocalNodeConfig]*
  ): Map[DbAccess, List[(String, LocalNodeConfig)]] = {
    // Basic attempt to normalize JDBC URL-based configuration and explicit property configuration
    // Limitations: Does not parse nor normalize the JDBC URLs
    def normalize(dbConfig: DbConfig): Option[DbAccess] = {
      import slick.util.ConfigExtensionMethods._

      val slickConfig = dbConfig.config

      def getPropStr(prop: String): Option[String] =
        slickConfig.getStringOpt(prop).orElse(slickConfig.getStringOpt(s"properties.$prop"))

      def getPropInt(prop: String): Option[Int] =
        slickConfig.getIntOpt(prop).orElse(slickConfig.getIntOpt(s"properties.$prop"))

      def extractUrl: Option[String] =
        getPropStr("url").orElse(getPropStr("jdbcUrl"))

      def extractServerPortDbAsUrl: Option[String] =
        for {
          server <- getPropStr("serverName")
          port <- getPropInt("portNumber")
          dbName <- getPropStr("databaseName")
          url = dbConfig match {
            case _: H2DbConfig => DbConfig.h2Url(dbName)
            case _: PostgresDbConfig => DbConfig.postgresUrl(server, port, dbName)
            // Assume Oracle
            case _ => DbConfig.oracleUrl(server, port, dbName)
          }
        } yield url

      val user = getPropStr("user")
      extractUrl.orElse(extractServerPortDbAsUrl).map(url => DbAccess(url = url, user = user))
    }

    // combine into a single list of name to config
    val configs = nodeConfigs.map(_.toList).foldLeft(List[(String, LocalNodeConfig)]())(_ ++ _)

    val withStorageConfigs = configs.mapFilter { case (name, config) =>
      config.storage match {
        case dbConfig: DbConfig => normalize(dbConfig).map((_, name, config))
        case _ => None
      }
    }

    withStorageConfigs
      .groupBy(_._1)
      .fmap(_.map { case (_, name, config) =>
        (name, config)
      })
  }

  private[config] def formatNodeList(nodes: List[(String, LocalNodeConfig)]): String =
    nodes.map { case (name, config) => s"${config.nodeTypeName} $name" }.mkString(",")

  /** Validate the config that the storage configuration is not shared between nodes. */
  private def noDuplicateStorage(
      config: CantonCommunityConfig
  ): Validated[NonEmpty[Seq[String]], Unit] = {
    val dbAccessToNodes =
      extractNormalizedDbAccess(config.participantsByString, config.domainsByString)

    dbAccessToNodes.toSeq
      .traverse_ {
        case (dbAccess, nodes) if nodes.lengthCompare(1) > 0 =>
          Validated.invalid(
            NonEmpty(Seq, s"Nodes ${formatNodeList(nodes)} share same DB access: $dbAccess")
          )
        case _ => Validated.valid(())
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
  private def atLeastOneNode(
      config: CantonCommunityConfig
  ): Validated[NonEmpty[Seq[String]], Unit] = {
    val CantonCommunityConfig(domains, participants, remoteDomains, remoteParticipants, _, _, _) =
      config
    Validated.cond(
      Seq(domains, participants, remoteDomains, remoteParticipants)
        .exists(_.nonEmpty),
      (),
      NonEmpty(Seq, "At least one node must be defined in the configuration"),
    )

  }

  /** Check that logging configs are backwards compatible but consistent */
  private def backwardsCompatibleLoggingConfig(
      config: CantonConfig
  ): Validated[NonEmpty[Seq[String]], Unit] = {
    (config.monitoring.logMessagePayloads, config.monitoring.logging.api.messagePayloads) match {
      case (Some(fst), Some(snd)) =>
        Validated.cond(
          fst == snd,
          (),
          NonEmpty(Seq, backwardsCompatibleLoggingConfigErr),
        )
      case _ => Valid
    }
  }

  private[config] val backwardsCompatibleLoggingConfigErr =
    "Inconsistent configuration of canton.monitoring.log-message-payloads and canton.monitoring.logging.api.message-payloads. Please use the latter in your configuration"

  private def developmentProtocolSafetyCheckDomains(
      config: CantonConfig
  ): Validated[NonEmpty[Seq[String]], Unit] = {
    developmentProtocolSafetyCheck(config.domains.toSeq.map { case (k, v) =>
      (k, v.domainParameters)
    })
  }

  private def warnIfUnsafeMinProtocolVersion(
      config: CantonConfig
  ): Validated[NonEmpty[Seq[String]], Unit] = {
    config.participants.toSeq.map { case (name, config) =>
      val minimum = config.parameters.minimumProtocolVersion.map(_.unwrap)
      if (minimum.getOrElse(ProtocolVersion.v2_0_0) == ProtocolVersion.v2_0_0)
        UnsafePvVersion2_0_0.WarnParticipant(name, minimum)
    }
    Validated.valid(())
  }

  private def warnIfUnsafeProtocolVersionEmbeddedDomain(
      config: CantonConfig
  ): Validated[NonEmpty[Seq[String]], Unit] = {
    config.domains.toSeq.map { case (name, config) =>
      val pv = config.domainParameters.protocolVersion.unwrap
      if (pv == ProtocolVersion.v2_0_0)
        UnsafePvVersion2_0_0.WarnDomain(name)
    }
    Validated.valid(())
  }

  private[config] def developmentProtocolSafetyCheck(
      namesAndConfig: Seq[(InstanceName, DomainParametersConfig)]
  ): Validated[NonEmpty[Seq[String]], Unit] = {
    def toNe(
        name: String,
        protocolVersion: ProtocolVersion,
        flag: Boolean,
    ): Validated[NonEmpty[Seq[String]], Unit] = {
      Validated.cond(
        protocolVersion != ProtocolVersion.unstable_development || flag,
        (),
        NonEmpty(
          Seq,
          s"Protocol ${protocolVersion} for node ${name} requires you to explicitly set ...parameters.will-corrupt-your-system-dev-version-support = yes",
        ),
      )
    }

    namesAndConfig.toList.traverse_ { case (name, parameters) =>
      toNe(
        name.unwrap,
        parameters.protocolVersion.version,
        parameters.willCorruptYourSystemDevVersionSupport,
      )
    }

  }
}
