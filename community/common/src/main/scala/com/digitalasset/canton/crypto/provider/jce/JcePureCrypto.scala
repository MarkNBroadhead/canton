// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto.provider.jce

import cats.syntax.either._
import com.digitalasset.canton.crypto.HkdfError.HkdfInternalError
import com.digitalasset.canton.crypto._
import com.digitalasset.canton.serialization.DeserializationError
import com.digitalasset.canton.util.ShowUtil
import com.digitalasset.canton.version.{HasVersionedToByteString, ProtocolVersion}
import com.google.crypto.tink.subtle.EllipticCurves.EcdsaEncoding
import com.google.crypto.tink.subtle.Enums.HashType
import com.google.crypto.tink.subtle._
import com.google.crypto.tink.{Aead, PublicKeySign, PublicKeyVerify}
import com.google.protobuf.ByteString
import org.bouncycastle.asn1.gm.GMObjectIdentifiers
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.{
  GeneralSecurityException,
  InvalidKeyException,
  NoSuchAlgorithmException,
  SecureRandom,
  SignatureException,
  PrivateKey => JPrivateKey,
  PublicKey => JPublicKey,
  Signature => JSignature,
}
import scala.collection.concurrent.TrieMap

object JceSecureRandom {

  /** Uses [[ThreadLocal]] here to reduce contention and improve performance. */
  private val random: ThreadLocal[SecureRandom] = new ThreadLocal[SecureRandom] {
    override def initialValue(): SecureRandom = newSecureRandom()
  }

  private def newSecureRandom() = {
    val rand = new SecureRandom()
    rand.nextLong()
    rand
  }

  private[jce] def generateRandomBytes(length: Int): Array[Byte] = {
    val randBytes = new Array[Byte](length)
    random.get().nextBytes(randBytes)
    randBytes
  }
}

class JcePureCrypto(
    javaKeyConverter: JceJavaConverter,
    override val defaultSymmetricKeyScheme: SymmetricKeyScheme,
    override val defaultHashAlgorithm: HashAlgorithm,
) extends CryptoPureApi
    with ShowUtil {

  // Cache for the java key conversion results
  private val javaPublicKeyCache: TrieMap[Fingerprint, Either[JavaKeyConversionError, JPublicKey]] =
    TrieMap.empty
  private val javaPrivateKeyCache
      : TrieMap[Fingerprint, Either[JavaKeyConversionError, JPrivateKey]] = TrieMap.empty

  private def checkKeyFormat[E](
      expected: CryptoKeyFormat,
      actual: CryptoKeyFormat,
      errFn: String => E,
  ): Either[E, Unit] =
    Either.cond(expected == actual, (), errFn(s"Expected key format $expected, but got $actual"))

  private def encryptAes128Gcm(
      plaintext: ByteString,
      symmetricKey: ByteString,
  ): Either[EncryptionError, ByteString] =
    for {
      encrypter <- Either
        .catchOnly[GeneralSecurityException](new AesGcmJce(symmetricKey.toByteArray))
        .leftMap(err => EncryptionError.InvalidSymmetricKey(err.toString))
      ciphertext <- Either
        .catchOnly[GeneralSecurityException](
          encrypter.encrypt(plaintext.toByteArray, Array[Byte]())
        )
        .leftMap(err => EncryptionError.FailedToEncrypt(err.toString))
    } yield ByteString.copyFrom(ciphertext)

  private def decryptAes128Gcm(
      ciphertext: ByteString,
      symmetricKey: ByteString,
  ): Either[DecryptionError, ByteString] =
    for {
      decrypter <- Either
        .catchOnly[GeneralSecurityException](new AesGcmJce(symmetricKey.toByteArray))
        .leftMap(err => DecryptionError.InvalidSymmetricKey(err.toString))
      plaintext <- Either
        .catchOnly[GeneralSecurityException](
          decrypter.decrypt(ciphertext.toByteArray, Array[Byte]())
        )
        .leftMap(err => DecryptionError.FailedToDecrypt(err.toString))
    } yield ByteString.copyFrom(plaintext)

  // Internal helper class for the symmetric encryption as part of the hybrid encryption scheme.
  private object Aes128GcmDemHelper extends EciesAeadHkdfDemHelper {

    override def getSymmetricKeySizeInBytes: Int = SymmetricKeyScheme.Aes128Gcm.keySizeInBytes

    override def getAead(symmetricKeyValue: Array[Byte]): Aead = new Aead {
      override def encrypt(plaintext: Array[Byte], associatedData: Array[Byte]): Array[Byte] = {
        val encrypter = new AesGcmJce(symmetricKeyValue)
        encrypter.encrypt(plaintext, associatedData)
      }

      override def decrypt(ciphertext: Array[Byte], associatedData: Array[Byte]): Array[Byte] = {
        val decrypter = new AesGcmJce(symmetricKeyValue)
        decrypter.decrypt(ciphertext, associatedData)
      }
    }
  }

  private def ecDsaSigner(
      signingKey: SigningPrivateKey,
      hashType: HashType,
  ): Either[SigningError, PublicKeySign] =
    for {
      _ <- checkKeyFormat(CryptoKeyFormat.Der, signingKey.format, SigningError.InvalidSigningKey)

      javaPrivateKey <- javaPrivateKeyCache
        .getOrElseUpdate(
          signingKey.id,
          javaKeyConverter
            .toJava(signingKey),
        )
        .leftMap(err =>
          SigningError.InvalidSigningKey(s"Failed to convert signing private key: $err")
        )

      ecPrivateKey <- javaPrivateKey match {
        case k: ECPrivateKey => Right(k)
        case _ =>
          Left(SigningError.InvalidSigningKey(s"Signing private key is not an EC private key"))
      }

      signer <- Either
        .catchOnly[GeneralSecurityException](
          new EcdsaSignJce(ecPrivateKey, hashType, EcdsaEncoding.DER)
        )
        .leftMap(err => SigningError.InvalidSigningKey(show"Failed to get signer for EC-DSA: $err"))
    } yield signer

  private def ecDsaVerifier(
      publicKey: SigningPublicKey,
      hashType: HashType,
  ): Either[SignatureCheckError, PublicKeyVerify] =
    for {
      _ <- checkKeyFormat(
        CryptoKeyFormat.Der,
        publicKey.format,
        SignatureCheckError.InvalidKeyError,
      )

      javaPublicKey <- javaPublicKeyCache
        .getOrElseUpdate(
          publicKey.id,
          javaKeyConverter
            .toJava(publicKey)
            .map(_._2),
        )
        .leftMap(err =>
          SignatureCheckError.InvalidKeyError(s"Failed to convert signing public key: $err")
        )

      ecPublicKey <- javaPublicKey match {
        case k: ECPublicKey => Right(k)
        case _ =>
          Left(SignatureCheckError.InvalidKeyError(s"Signing public key is not an EC public key"))
      }

      verifier <- Either
        .catchOnly[GeneralSecurityException](
          new EcdsaVerifyJce(ecPublicKey, hashType, EcdsaEncoding.DER)
        )
        .leftMap(err =>
          SignatureCheckError.InvalidKeyError(s"Failed to get signer for Ed25519: $err")
        )
    } yield verifier

  override def generateSymmetricKey(
      scheme: SymmetricKeyScheme
  ): Either[EncryptionKeyGenerationError, SymmetricKey] =
    scheme match {
      case SymmetricKeyScheme.Aes128Gcm =>
        val key128 = generateRandomByteString(scheme.keySizeInBytes)
        Right(SymmetricKey(CryptoKeyFormat.Raw, key128, scheme))
    }

  override def createSymmetricKey(
      bytes: SecureRandomness,
      scheme: SymmetricKeyScheme,
  ): Either[EncryptionKeyCreationError, SymmetricKey] = {
    val randomnessLength = bytes.unwrap.size()
    val keyLength = scheme.keySizeInBytes

    for {
      _ <- Either.cond(
        randomnessLength == keyLength,
        (),
        EncryptionKeyCreationError.InvalidRandomnessLength(randomnessLength, keyLength),
      )
      key = scheme match {
        case SymmetricKeyScheme.Aes128Gcm =>
          SymmetricKey(CryptoKeyFormat.Raw, bytes.unwrap, scheme)
      }
    } yield key
  }

  override protected[crypto] def sign(
      bytes: ByteString,
      signingKey: SigningPrivateKey,
  ): Either[SigningError, Signature] = {

    def signWithSigner(signer: PublicKeySign): Either[SigningError, Signature] =
      Either
        .catchOnly[GeneralSecurityException](signer.sign(bytes.toByteArray))
        .bimap(
          err => SigningError.FailedToSign(show"$err"),
          signatureBytes =>
            new Signature(SignatureFormat.Raw, ByteString.copyFrom(signatureBytes), signingKey.id),
        )

    signingKey.scheme match {
      case SigningKeyScheme.Ed25519 =>
        for {
          _ <- checkKeyFormat(
            CryptoKeyFormat.Raw,
            signingKey.format,
            SigningError.InvalidSigningKey,
          )
          signer <- Either
            .catchOnly[GeneralSecurityException](new Ed25519Sign(signingKey.key.toByteArray))
            .leftMap(err =>
              SigningError.InvalidSigningKey(show"Failed to get signer for Ed25519: $err")
            )
          signature <- signWithSigner(signer)
        } yield signature

      case SigningKeyScheme.EcDsaP256 =>
        ecDsaSigner(signingKey, HashType.SHA256).flatMap(signWithSigner)
      case SigningKeyScheme.EcDsaP384 =>
        ecDsaSigner(signingKey, HashType.SHA384).flatMap(signWithSigner)

      case SigningKeyScheme.Sm2 =>
        for {
          signer <- Either
            .catchOnly[NoSuchAlgorithmException](
              JSignature.getInstance(GMObjectIdentifiers.sm2sign_with_sm3.toString)
            )
            .leftMap(SigningError.GeneralError)
          javaPrivateKey <- javaPrivateKeyCache
            .getOrElseUpdate(
              signingKey.id,
              javaKeyConverter
                .toJava(signingKey),
            )
            .leftMap(err =>
              SigningError.InvalidSigningKey(s"Failed to convert signing private key: $err")
            )
          _ <- Either
            .catchOnly[InvalidKeyException](signer.initSign(javaPrivateKey))
            .leftMap(err => SigningError.InvalidSigningKey(show"$err"))
          _ <- Either
            .catchOnly[SignatureException](signer.update(bytes.toByteArray))
            .leftMap(err => SigningError.FailedToSign(show"$err"))
          signatureBytes <- Either
            .catchOnly[SignatureException](signer.sign())
            .map(ByteString.copyFrom)
            .leftMap(err => SigningError.FailedToSign(show"$err"))
        } yield new Signature(SignatureFormat.Raw, signatureBytes, signingKey.id)
    }
  }

  override protected[crypto] def verifySignature(
      bytes: ByteString,
      publicKey: SigningPublicKey,
      signature: Signature,
  ): Either[SignatureCheckError, Unit] = {

    def verify(verifier: PublicKeyVerify): Either[SignatureCheckError, Unit] =
      Either
        .catchOnly[GeneralSecurityException](
          verifier.verify(signature.unwrap.toByteArray, bytes.toByteArray)
        )
        .leftMap(err =>
          SignatureCheckError
            .InvalidSignature(signature, bytes, s"Failed to verify signature: $err")
        )

    for {
      _ <- Either.cond(
        signature.signedBy == publicKey.id,
        (),
        SignatureCheckError.SignatureWithWrongKey(
          s"Signature signed by ${signature.signedBy} instead of ${publicKey.id}"
        ),
      )

      _ <- publicKey.scheme match {
        case SigningKeyScheme.Ed25519 =>
          for {
            _ <- checkKeyFormat(
              CryptoKeyFormat.Raw,
              publicKey.format,
              SignatureCheckError.InvalidKeyError,
            )
            verifier <- Either
              .catchOnly[GeneralSecurityException](new Ed25519Verify(publicKey.key.toByteArray))
              .leftMap(err =>
                SignatureCheckError.InvalidKeyError(show"Failed to get signer for Ed25519: $err")
              )
            _ <- verify(verifier)
          } yield ()

        case SigningKeyScheme.EcDsaP256 => ecDsaVerifier(publicKey, HashType.SHA256).flatMap(verify)
        case SigningKeyScheme.EcDsaP384 => ecDsaVerifier(publicKey, HashType.SHA384).flatMap(verify)

        case SigningKeyScheme.Sm2 =>
          for {
            signer <- Either
              .catchOnly[NoSuchAlgorithmException](
                JSignature.getInstance(GMObjectIdentifiers.sm2sign_with_sm3.toString)
              )
              .leftMap(SignatureCheckError.GeneralError)
            javaPublicKey <- javaPublicKeyCache
              .getOrElseUpdate(
                publicKey.id,
                javaKeyConverter
                  .toJava(publicKey)
                  .map(_._2),
              )
              .leftMap(err =>
                SignatureCheckError.InvalidKeyError(s"Failed to convert signing public key: $err")
              )
            _ <- Either
              .catchOnly[InvalidKeyException](signer.initVerify(javaPublicKey))
              .leftMap(err => SignatureCheckError.InvalidKeyError(show"$err"))
            _ <- Either
              .catchOnly[SignatureException](signer.update(bytes.toByteArray))
              .leftMap(SignatureCheckError.GeneralError)
            result <- Either
              .catchOnly[SignatureException](signer.verify(signature.unwrap.toByteArray))
              .leftMap(err => SignatureCheckError.InvalidSignature(signature, bytes, show"$err"))
            _ <- Either.cond(
              result,
              (),
              SignatureCheckError.InvalidSignature(signature, bytes, "signature verification false"),
            )
          } yield ()
      }
    } yield ()
  }

  override def encryptWith[M <: HasVersionedToByteString](
      message: M,
      publicKey: EncryptionPublicKey,
      version: ProtocolVersion,
  ): Either[EncryptionError, AsymmetricEncrypted[M]] = publicKey.scheme match {
    case EncryptionKeyScheme.EciesP256HkdfHmacSha256Aes128Gcm =>
      for {
        _ <- checkKeyFormat(
          CryptoKeyFormat.Der,
          publicKey.format,
          EncryptionError.InvalidEncryptionKey,
        )
        javaPublicKey <- javaPublicKeyCache
          .getOrElseUpdate(
            publicKey.id,
            javaKeyConverter
              .toJava(publicKey)
              .map(_._2),
          )
          .leftMap(err => EncryptionError.InvalidEncryptionKey(err.toString))
        ecPublicKey <- javaPublicKey match {
          case k: ECPublicKey => Right(k)
          case _ =>
            Left(EncryptionError.InvalidEncryptionKey(s"Public key $publicKey is not an EC key"))
        }
        encrypter <- Either
          .catchOnly[GeneralSecurityException](
            new EciesAeadHkdfHybridEncrypt(
              ecPublicKey,
              Array[Byte](),
              "HmacSha256",
              EllipticCurves.PointFormatType.UNCOMPRESSED,
              Aes128GcmDemHelper,
            )
          )
          .leftMap(err => EncryptionError.InvalidEncryptionKey(err.toString))
        ciphertext <- Either
          .catchOnly[GeneralSecurityException](
            encrypter
              .encrypt(
                message.toByteString(version).toByteArray,
                Array[Byte](),
              )
          )
          .leftMap(err => EncryptionError.FailedToEncrypt(err.toString))
        encrypted = new AsymmetricEncrypted[M](
          ByteString.copyFrom(ciphertext),
          publicKey.fingerprint,
        )
      } yield encrypted
  }

  override protected def decryptWithInternal[M](
      encrypted: AsymmetricEncrypted[M],
      privateKey: EncryptionPrivateKey,
  )(
      deserialize: ByteString => Either[DeserializationError, M]
  ): Either[DecryptionError, M] =
    privateKey.scheme match {
      case EncryptionKeyScheme.EciesP256HkdfHmacSha256Aes128Gcm =>
        for {
          _ <- checkKeyFormat(
            CryptoKeyFormat.Der,
            privateKey.format,
            DecryptionError.InvalidEncryptionKey,
          )
          javaPrivateKey <- javaPrivateKeyCache
            .getOrElseUpdate(
              privateKey.id,
              javaKeyConverter
                .toJava(privateKey),
            )
            .leftMap(err => DecryptionError.InvalidEncryptionKey(err.toString))
          ecPrivateKey <- javaPrivateKey match {
            case k: ECPrivateKey => Right(k)
            case _ =>
              Left(
                DecryptionError.InvalidEncryptionKey(
                  s"Private key ${privateKey.id} is not an EC key"
                )
              )
          }
          decrypter <- Either
            .catchOnly[GeneralSecurityException](
              new EciesAeadHkdfHybridDecrypt(
                ecPrivateKey,
                Array[Byte](),
                "HmacSha256",
                EllipticCurves.PointFormatType.UNCOMPRESSED,
                Aes128GcmDemHelper,
              )
            )
            .leftMap(err => DecryptionError.InvalidEncryptionKey(err.toString))
          plaintext <- Either
            .catchOnly[GeneralSecurityException](
              decrypter.decrypt(encrypted.ciphertext.toByteArray, Array[Byte]())
            )
            .leftMap(err => DecryptionError.FailedToDecrypt(err.toString))
          message <- deserialize(ByteString.copyFrom(plaintext))
            .leftMap(DecryptionError.FailedToDeserialize)
        } yield message
    }

  override def encryptWith[M <: HasVersionedToByteString](
      message: M,
      symmetricKey: SymmetricKey,
      version: ProtocolVersion,
  ): Either[EncryptionError, Encrypted[M]] =
    symmetricKey.scheme match {
      case SymmetricKeyScheme.Aes128Gcm =>
        for {
          _ <- checkKeyFormat(
            CryptoKeyFormat.Raw,
            symmetricKey.format,
            EncryptionError.InvalidSymmetricKey,
          )
          encryptedBytes <- encryptAes128Gcm(
            message.toByteString(version),
            symmetricKey.key,
          )
          encrypted = new Encrypted[M](encryptedBytes)
        } yield encrypted
    }

  override def decryptWith[M](encrypted: Encrypted[M], symmetricKey: SymmetricKey)(
      deserialize: ByteString => Either[DeserializationError, M]
  ): Either[DecryptionError, M] =
    symmetricKey.scheme match {
      case SymmetricKeyScheme.Aes128Gcm =>
        for {
          _ <- checkKeyFormat(
            CryptoKeyFormat.Raw,
            symmetricKey.format,
            DecryptionError.InvalidSymmetricKey,
          )
          plaintext <- decryptAes128Gcm(encrypted.ciphertext, symmetricKey.key)
          message <- deserialize(plaintext).leftMap(DecryptionError.FailedToDeserialize)
        } yield message
    }

  private def hkdf(
      params: HKDFParameters,
      outputBytes: Int,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    val output = Array.fill[Byte](outputBytes)(0)
    val digest = algorithm match {
      case HmacAlgorithm.HmacSha256 => new SHA256Digest()
    }
    val generator = new HKDFBytesGenerator(digest)

    for {
      generated <-
        Either
          .catchNonFatal {
            generator.init(params)
            generator.generateBytes(output, 0, outputBytes)
          }
          .leftMap(err => HkdfInternalError(show"Failed to compute HKDF with JCE: $err"))
      _ <- Either.cond(
        generated == outputBytes,
        (),
        HkdfInternalError(s"Generated only $generated bytes instead of $outputBytes"),
      )
      expansion <- SecureRandomness
        .fromByteString(outputBytes)(ByteString.copyFrom(output))
        .leftMap(err => HkdfInternalError(s"Invalid output from HKDF: $err"))
    } yield expansion
  }

  override protected def computeHkdfInternal(
      keyMaterial: ByteString,
      outputBytes: Int,
      info: HkdfInfo,
      salt: ByteString,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    val params =
      new HKDFParameters(keyMaterial.toByteArray, salt.toByteArray, info.bytes.toByteArray)
    hkdf(params, outputBytes, algorithm)
  }

  override protected def hkdfExpandInternal(
      keyMaterial: SecureRandomness,
      outputBytes: Int,
      info: HkdfInfo,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    val params =
      HKDFParameters.skipExtractParameters(keyMaterial.unwrap.toByteArray, info.bytes.toByteArray)
    hkdf(params, outputBytes, algorithm)
  }

  override protected def generateRandomBytes(length: Int): Array[Byte] =
    JceSecureRandom.generateRandomBytes(length)
}
