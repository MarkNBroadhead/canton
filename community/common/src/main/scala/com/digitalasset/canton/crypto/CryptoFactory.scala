// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto

import cats.data.EitherT
import cats.instances.future._
import cats.syntax.either._
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.{
  CryptoConfig,
  CryptoProvider,
  CryptoProviderScheme,
  CryptoSchemeConfig,
  ProcessingTimeout,
}
import com.digitalasset.canton.crypto.provider.jce.{
  JceJavaConverter,
  JcePrivateCrypto,
  JcePureCrypto,
}
import com.digitalasset.canton.crypto.provider.tink.{
  TinkJavaConverter,
  TinkPrivateCrypto,
  TinkPureCrypto,
}
import com.digitalasset.canton.crypto.store.{CryptoPrivateStore, CryptoPublicStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.Storage
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.Security
import scala.concurrent.{ExecutionContext, Future}

object CryptoFactory {

  case class CryptoScheme[S](default: S, allowed: NonEmpty[Set[S]])

  def selectSchemes[S](
      configured: CryptoSchemeConfig[S],
      provider: CryptoProviderScheme[S],
  ): Either[String, CryptoScheme[S]] = {
    val supported = provider.supported

    // If no allowed schemes are configured, all supported schemes are allowed.
    val allowed = configured.allowed.getOrElse(supported)

    // If no scheme is configured, use the default scheme of the provider
    val default = configured.default.getOrElse(provider.default)

    // The allowed schemes that are not in the supported set
    val unsupported = allowed.diff(supported)

    for {
      _ <- Either.cond(unsupported.isEmpty, (), s"Allowed schemes $unsupported are not supported")
      _ <- Either.cond(allowed.contains(default), (), s"Scheme $default is not allowed: $allowed")
    } yield CryptoScheme(default, allowed)
  }

  def selectAllowedSymmetricKeySchemes(
      config: CryptoConfig
  ): Either[String, NonEmpty[Set[SymmetricKeyScheme]]] =
    selectSchemes(config.symmetric, config.provider.symmetric).map(_.allowed)

  def selectAllowedHashAlgorithms(
      config: CryptoConfig
  ): Either[String, NonEmpty[Set[HashAlgorithm]]] =
    selectSchemes(config.hash, config.provider.hash).map(_.allowed)

  def selectAllowedSigningKeyScheme(
      config: CryptoConfig
  ): Either[String, NonEmpty[Set[SigningKeyScheme]]] =
    selectSchemes(config.signing, config.provider.signing).map(_.allowed)

  def selectAllowedEncryptionKeyScheme(
      config: CryptoConfig
  ): Either[String, NonEmpty[Set[EncryptionKeyScheme]]] =
    selectSchemes(config.encryption, config.provider.encryption).map(_.allowed)

  def create(
      config: CryptoConfig,
      storage: Storage,
      timeouts: ProcessingTimeout,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext
  ): EitherT[Future, String, Crypto] = {
    val cryptoPrivateStore = CryptoPrivateStore.create(storage, timeouts, loggerFactory)
    val cryptoPublicStore = CryptoPublicStore.create(storage, timeouts, loggerFactory)
    for {
      symmetricKeyScheme <- selectSchemes(config.symmetric, config.provider.symmetric)
        .map(_.default)
        .toEitherT
      hashAlgorithm <- selectSchemes(config.hash, config.provider.hash).map(_.default).toEitherT
      signingKeyScheme <- selectSchemes(config.signing, config.provider.signing)
        .map(_.default)
        .toEitherT
      encryptionKeyScheme <- selectSchemes(config.encryption, config.provider.encryption)
        .map(_.default)
        .toEitherT
      crypto <- config.provider match {
        case CryptoProvider.Tink =>
          for {
            pureCrypto <- TinkPureCrypto.create(symmetricKeyScheme, hashAlgorithm).toEitherT
            privateCrypto = TinkPrivateCrypto.create(
              pureCrypto,
              signingKeyScheme,
              encryptionKeyScheme,
              cryptoPrivateStore,
            )
            javaKeyConverter = new TinkJavaConverter(pureCrypto.defaultHashAlgorithm)
            crypto = new Crypto(
              pureCrypto,
              privateCrypto,
              cryptoPrivateStore,
              cryptoPublicStore,
              javaKeyConverter,
              timeouts,
              loggerFactory,
            )
          } yield crypto
        case CryptoProvider.Jce =>
          Security.addProvider(new BouncyCastleProvider)
          val javaKeyConverter = new JceJavaConverter(hashAlgorithm)
          val pureCrypto = new JcePureCrypto(javaKeyConverter, symmetricKeyScheme, hashAlgorithm)
          val privateCrypto =
            new JcePrivateCrypto(
              pureCrypto,
              signingKeyScheme,
              encryptionKeyScheme,
              cryptoPrivateStore,
            )
          EitherT.rightT[Future, String](
            new Crypto(
              pureCrypto,
              privateCrypto,
              cryptoPrivateStore,
              cryptoPublicStore,
              javaKeyConverter,
              timeouts,
              loggerFactory,
            )
          )
      }
    } yield crypto
  }

  def createPureCrypto(config: CryptoConfig): Either[String, CryptoPureApi] =
    for {
      symmetricKeyScheme <- selectSchemes(config.symmetric, config.provider.symmetric)
        .map(_.default)
      hashAlgorithm <- selectSchemes(config.hash, config.provider.hash).map(_.default)
      crypto <- config.provider match {
        case CryptoProvider.Tink =>
          TinkPureCrypto.create(symmetricKeyScheme, hashAlgorithm)
        case CryptoProvider.Jce =>
          val javaKeyConverter = new JceJavaConverter(hashAlgorithm)
          Right(new JcePureCrypto(javaKeyConverter, symmetricKeyScheme, hashAlgorithm))
      }
    } yield crypto

}
