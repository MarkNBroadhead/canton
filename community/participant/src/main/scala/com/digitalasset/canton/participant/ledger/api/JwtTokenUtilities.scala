// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.ledger.api

import com.daml.jwt.JwtSigner
import com.daml.jwt.domain.{DecodedJwt, Jwt}
import com.daml.ledger.api.auth.{AuthServiceJWTCodec, CustomDamlJWTPayload}

/** some helpers in other to use with ad-hoc JWT authentication */
object JwtTokenUtilities {

  def buildUnsafeToken(
      secret: String,
      admin: Boolean,
      readAs: List[String],
      actAs: List[String],
      ledgerId: Option[String] = None,
      applicationId: Option[String] = None,
  ): String = {
    val payload = CustomDamlJWTPayload(
      ledgerId = ledgerId,
      None,
      applicationId = applicationId,
      None,
      admin = admin,
      readAs = readAs,
      actAs = actAs,
    )
    // stolen from com.daml.ledger.api.auth.Main
    val jwtPayload = AuthServiceJWTCodec.compactPrint(payload)
    val jwtHeader = s"""{"alg": "HS256", "typ": "JWT"}"""
    val signed: Jwt = JwtSigner.HMAC256
      .sign(DecodedJwt(jwtHeader, jwtPayload), secret)
      .valueOr(err => throw new RuntimeException(err.message))
    signed.value
  }

}
