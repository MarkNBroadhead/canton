// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.mediator.admin.gprc

import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.crypto.SigningPublicKey
import com.digitalasset.canton.domain.admin.v0
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.version.HasProtoV0

trait InitializeMediatorResponse extends HasProtoV0[v0.InitializeMediatorResponse] {
  override def toProtoV0: v0.InitializeMediatorResponse // make public
  def toEither: Either[String, SigningPublicKey]
}

object InitializeMediatorResponse {
  case class Success(mediatorKey: SigningPublicKey) extends InitializeMediatorResponse {
    override def toProtoV0: v0.InitializeMediatorResponse =
      v0.InitializeMediatorResponse(
        v0.InitializeMediatorResponse.Value.Success(
          v0.InitializeMediatorResponse.Success(
            Some(mediatorKey.toProtoV0)
          )
        )
      )

    override def toEither: Either[String, SigningPublicKey] = Right(mediatorKey)
  }

  case class Failure(reason: String) extends InitializeMediatorResponse {
    override def toProtoV0: v0.InitializeMediatorResponse =
      v0.InitializeMediatorResponse(
        v0.InitializeMediatorResponse.Value.Failure(
          v0.InitializeMediatorResponse.Failure(
            reason
          )
        )
      )

    override def toEither: Either[String, SigningPublicKey] = Left(reason)
  }

  def fromProtoV0(
      responseP: v0.InitializeMediatorResponse
  ): ParsingResult[InitializeMediatorResponse] = {
    def success(
        successP: v0.InitializeMediatorResponse.Success
    ): ParsingResult[InitializeMediatorResponse] =
      for {
        mediatorKey <- ProtoConverter.parseRequired(
          SigningPublicKey.fromProtoV0,
          "mediator_key",
          successP.mediatorKey,
        )
      } yield InitializeMediatorResponse.Success(mediatorKey)

    def failure(
        failureP: v0.InitializeMediatorResponse.Failure
    ): ParsingResult[InitializeMediatorResponse] =
      Right(InitializeMediatorResponse.Failure(failureP.reason))

    responseP.value match {
      case v0.InitializeMediatorResponse.Value.Empty =>
        Left(ProtoDeserializationError.FieldNotSet("value"))
      case v0.InitializeMediatorResponse.Value.Success(value) => success(value)
      case v0.InitializeMediatorResponse.Value.Failure(value) => failure(value)
    }
  }
}
