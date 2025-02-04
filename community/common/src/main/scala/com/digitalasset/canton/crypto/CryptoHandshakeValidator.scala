// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.CryptoConfig
import com.digitalasset.canton.crypto.CryptoFactory.CryptoScheme
import com.digitalasset.canton.protocol.StaticDomainParameters

object CryptoHandshakeValidator {

  private def validateScheme[S](
      required: NonEmpty[Set[S]],
      schemeE: Either[String, CryptoScheme[S]],
  ): Either[String, Unit] =
    for {
      scheme <- schemeE

      // Required but not allowed
      unsupported = required.diff(scheme.allowed)
      _ <- Either.cond(
        unsupported.isEmpty,
        (),
        s"Required schemes $unsupported are not supported/allowed",
      )

      // The default scheme must be a required scheme, otherwise another node may not allow and support our default scheme.
      _ <- Either.cond(
        required.contains(scheme.default),
        (),
        s"The default ${scheme.default} scheme is not a required scheme: $required",
      )
    } yield ()

  /** Validates that the required crypto schemes are allowed and supported. The default scheme must be one of the required schemes.
    *
    * The domain defines for each signing, encryption, symmetric, and hashing a set of required schemes.
    * A connecting member must be configured to allow (and thus support) all required schemes of the domain.
    */
  def validate(parameters: StaticDomainParameters, config: CryptoConfig): Either[String, Unit] =
    for {
      _ <- validateScheme(
        parameters.requiredSigningKeySchemes,
        CryptoFactory.selectSchemes(config.signing, config.provider.signing),
      )
      _ <- validateScheme(
        parameters.requiredEncryptionKeySchemes,
        CryptoFactory.selectSchemes(config.encryption, config.provider.encryption),
      )
      _ <- validateScheme(
        parameters.requiredSymmetricKeySchemes,
        CryptoFactory.selectSchemes(config.symmetric, config.provider.symmetric),
      )
      _ <- validateScheme(
        parameters.requiredHashAlgorithms,
        CryptoFactory.selectSchemes(config.hash, config.provider.hash),
      )
      requiredFormats = parameters.requiredCryptoKeyFormats
      unsupportedFormats = requiredFormats.diff(config.provider.supportedCryptoKeyFormats)
      _ <- Either.cond(
        unsupportedFormats.isEmpty,
        (),
        s"Required schemes $requiredFormats are not supported/allowed: $unsupportedFormats",
      )
    } yield ()

}
