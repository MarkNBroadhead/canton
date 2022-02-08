// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer

import akka.stream.Materializer
import com.digitalasset.canton.DomainId
import com.digitalasset.canton.config.LocalNodeParameters
import com.digitalasset.canton.crypto.DomainSyncCryptoClient
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

trait SequencerFactory {
  def create(
      domainId: DomainId,
      storage: Storage,
      clock: Clock,
      domainSyncCryptoApi: DomainSyncCryptoClient,
      snapshot: Option[SequencerSnapshot],
      localNodeParameters: LocalNodeParameters,
  )(implicit ec: ExecutionContext, tracer: Tracer, actorMaterializer: Materializer): Sequencer
}

object SequencerFactory {
  def database(
      config: DatabaseSequencerConfig,
      writerStorageFactory: SequencerWriterStoreFactory,
      loggerFactory: NamedLoggerFactory,
  ): SequencerFactory =
    new SequencerFactory {
      override def create(
          domainId: DomainId,
          storage: Storage,
          clock: Clock,
          domainSyncCryptoApi: DomainSyncCryptoClient,
          snapshot: Option[SequencerSnapshot],
          localNodeParameters: LocalNodeParameters,
      )(implicit
          ec: ExecutionContext,
          tracer: Tracer,
          actorMaterializer: Materializer,
      ): Sequencer = {
        val sequencer = new DatabaseSequencer(
          writerStorageFactory,
          config,
          localNodeParameters.processingTimeouts,
          storage,
          clock,
          domainId,
          domainSyncCryptoApi,
          loggerFactory,
        )

        config.testingInterceptor.map(_(clock)(sequencer)(ec)).getOrElse(sequencer)
      }
    }
}
