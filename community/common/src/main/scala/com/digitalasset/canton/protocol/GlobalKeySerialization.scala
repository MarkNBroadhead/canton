// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol

import cats.syntax.either._
import com.daml.lf.value.ValueCoder.{CidEncoder => LfDummyCidEncoder}
import com.daml.lf.value.{ValueCoder, ValueOuterClass}
import com.digitalasset.canton.{LfVersioned, ProtoDeserializationError}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.util.LfTransactionUtil

object GlobalKeySerialization {

  def toProto(globalKey: LfVersioned[LfGlobalKey]): Either[String, v0.GlobalKey] = {
    val serializedTemplateId =
      ValueCoder.encodeIdentifier(globalKey.unversioned.templateId).toByteString
    for {
      // Contract keys are not allowed to hold contract ids; therefore it is "okay"
      // to use a dummy LfContractId encoder.
      serializedKey <- ValueCoder
        .encodeVersionedValue(LfDummyCidEncoder, globalKey.version, globalKey.unversioned.key)
        .map(_.toByteString)
        .leftMap(_.errorMessage)
    } yield v0.GlobalKey(serializedTemplateId, serializedKey)
  }

  def assertToProto(key: LfVersioned[LfGlobalKey]): v0.GlobalKey =
    toProto(key)
      .fold(
        err => throw new IllegalArgumentException(s"Can't encode contract key: $err"),
        identity,
      )

  def fromProtoV0(protoKey: v0.GlobalKey): ParsingResult[LfVersioned[LfGlobalKey]] =
    for {
      pTemplateId <- ProtoConverter.protoParser(ValueOuterClass.Identifier.parseFrom)(
        protoKey.templateId
      )
      templateId <- ValueCoder
        .decodeIdentifier(pTemplateId)
        .leftMap(err =>
          ProtoDeserializationError
            .ValueDeserializationError("GlobalKey.templateId", err.errorMessage)
        )
      deserializedProtoKey <- ProtoConverter.protoParser(ValueOuterClass.VersionedValue.parseFrom)(
        protoKey.key
      )
      unsafeKeyVersioned <- ValueCoder
        .decodeVersionedValue(ValueCoder.CidDecoder, deserializedProtoKey)
        .leftMap(err =>
          ProtoDeserializationError.ValueDeserializationError("GlobalKey.proto", err.toString)
        )
      key <- LfTransactionUtil
        .checkNoContractIdInKey(unsafeKeyVersioned)
        .leftMap(cid =>
          ProtoDeserializationError
            .ValueDeserializationError("GlobalKey.key", s"Key contains contract Id $cid")
        )
    } yield LfVersioned(key.version, LfGlobalKey(templateId, key.unversioned))

}
