package com.terminox.data.repository

import com.terminox.domain.repository.CertificateRepository
import com.terminox.security.CertificateGenerationResult
import com.terminox.security.CertificateGenerator
import javax.inject.Inject
import javax.inject.Singleton
import android.security.keystore.KeyProperties

@Singleton
class CertificateRepositoryImpl @Inject constructor(
    private val certificateGenerator: CertificateGenerator
) : CertificateRepository {

    override fun generateClientCertificate(
        alias: String,
        subjectDN: String,
        keyAlgorithm: String,
        keySize: Int
    ): CertificateGenerationResult? {
        return when (keyAlgorithm) {
            KeyProperties.KEY_ALGORITHM_RSA -> certificateGenerator.generateRsaKeyPairAndCertificate(alias, subjectDN, keySize)
            KeyProperties.KEY_ALGORITHM_EC -> certificateGenerator.generateEcdsaKeyPairAndCertificate(alias, subjectDN, keySize)
            else -> null // Unsupported key algorithm
        }
    }

    override fun getClientCertificate(alias: String): CertificateGenerationResult? {
        return certificateGenerator.getCertificateAndPrivateKey(alias)
    }

    override fun deleteClientCertificate(alias: String): Boolean {
        return certificateGenerator.deleteKey(alias)
    }
}
