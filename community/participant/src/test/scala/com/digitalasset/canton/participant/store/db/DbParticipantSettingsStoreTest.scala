// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store.db

import com.digitalasset.canton.participant.store.ParticipantSettingsStoreTest
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.{DbTest, H2Test, PostgresTest}
import io.functionmeta.functionFullName

import scala.concurrent.Future

trait DbParticipantSettingsStoreTest extends ParticipantSettingsStoreTest with DbTest {

  override def cleanDb(storage: DbStorage): Future[Unit] = {
    import storage.api._
    storage.update_(sqlu"truncate table participant_settings", functionFullName)
  }

  def mk(s: DbStorage): DbParticipantSettingsStore =
    new DbParticipantSettingsStore(s, timeouts, loggerFactory)(parallelExecutionContext)

  "DbParticipantResourceManagementStore" must {
    behave like participantSettingsStore(() => mk(storage))
  }

}

class ParticipantSettingsStoreTestH2 extends DbParticipantSettingsStoreTest with H2Test

class ParticipantSettingsStoreTestPostgres extends DbParticipantSettingsStoreTest with PostgresTest
