// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.ledger.api

import cats.syntax.either._
import com.digitalasset.canton.LedgerParticipantId
import com.digitalasset.canton.config.{DbConfig, MemoryStorageConfig, StorageConfig}
import com.digitalasset.canton.participant.ledger.api.CantonLedgerApiServerWrapper.{
  FailedToStopLedgerApiServer,
  LedgerApiServerError,
}
import com.digitalasset.canton.util.ResourceUtil.withResource
import com.digitalasset.canton.DiscardOps

import java.sql.{DriverManager, SQLException}
import java.util.UUID.randomUUID
import scala.concurrent.blocking
import scala.util.{Failure, Success, Try}

/** Configuration and actions for the ledger-api persistence,
  * Actions are synchronous as they use the underlying jdbc driver directly and there is no simple version of async calls available.
  * Given these are only used for one off rare actions this is currently sufficient. Just be aware these operations will block.
  */
class LedgerApiStorage private[api] (
    val jdbcUrl: String,
    createAction: () => Unit,
    closeAction: String => Unit,
) {

  def createSchema(): Either[SQLException, Unit] =
    asEitherT(_ => createAction(), Predef.identity)

  def close(): Either[FailedToStopLedgerApiServer, Unit] =
    asEitherT(closeAction, FailedToStopLedgerApiServer(s"Closing storage failed with error", _))

  private def asEitherT[E](action: String => Unit, error: SQLException => E): Either[E, Unit] =
    blocking {
      Either.catchOnly[SQLException](action(jdbcUrl)).leftMap(error)
    }
}

/** Different approaches for erasing and closing ledger-api persisted data */
private[api] object DbActions {

  import LedgerApiStorage.ledgerApiSchemaName

  /** It's in its own h2 instance so just shut it down */
  def shutdownH2(url: String): Unit = runSqlQuery(url, "shutdown").discard[Try[Unit]]

  def createSchemaIfNotExists(url: String): Unit =
    runSqlQuery(url, s"create schema if not exists $ledgerApiSchemaName") match {
      case Failure(exception) => throw exception
      case Success(_) => ()
    }

  /** Do nothing. Useful comment. */
  def doNothing(url: String): Unit = ()

  private def runSqlQuery(url: String, query: String): Try[Unit] =
    Try { // TODO(#8294) - track down cause of SQLException: "No suitable driver found for jdbc:h2:mem:ledger_api_participant" upon shutdown introduced with H2 2.0.206 upgrade
      withResource(DriverManager.getConnection(url)) { connection =>
        withResource(connection.createStatement())(_.execute(query))
      }.discard[Boolean]
    }
}

object LedgerApiStorage {

  // should match what is created in the sql migrations
  val ledgerApiSchemaName = "ledger_api"

  def fromDbConfig(dbConfig: DbConfig): Either[LedgerApiServerError, LedgerApiStorage] =
    LedgerApiJdbcUrl
      .fromDbConfig(dbConfig)
      // for h2 don't explicitly shutdown on close as it could still be being used by canton
      .map(jdbcUrl =>
        new LedgerApiStorage(
          jdbcUrl.url,
          () => jdbcUrl.createLedgerApiSchemaIfNotExists(),
          DbActions.doNothing,
        )
      )

  def fromStorageConfig(
      config: StorageConfig,
      participantId: LedgerParticipantId,
  ): Either[LedgerApiServerError, LedgerApiStorage] =
    (config: @unchecked) match {
      // although canton is running using in-memory data structures, ledger-api still needs a sql database so allocate its own h2 instance
      case _: MemoryStorageConfig => allocateInMemoryH2Database(participantId)
      // reuse the configured database
      case dbConfig: DbConfig =>
        fromDbConfig(dbConfig)
    }

  private def allocateInMemoryH2Database(
      participantId: LedgerParticipantId
  ): Either[LedgerApiServerError, LedgerApiStorage] = {
    val sanitizedParticipantId = (participantId + "_" + randomUUID.toString).filter { c =>
      (c.isLetterOrDigit || c == '_') && c.toInt <= 127
    }

    // we know ledger-api is the sole user of the database so can shutdown on close without negatively impacting canton
    Right(
      new LedgerApiStorage(
        s"jdbc:h2:mem:ledger_api_$sanitizedParticipantId;DB_CLOSE_DELAY=-1",
        () => (),
        DbActions.shutdownH2,
      )
    )
  }
}
