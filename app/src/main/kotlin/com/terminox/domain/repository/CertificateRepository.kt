package com.terminox.domain.repository

import com.terminox.security.CertificateGenerationResult

interface CertificateRepository {
    /**
     * Generates a new client certificate and key pair and stores them securely.
     * @param alias A unique alias for the certificate and key pair.
     * @param subjectDN The Subject Distinguished Name for the certificate.
     * @param keyAlgorithm The algorithm for the key pair (e.g., "RSA", "EC").
     * @param keySize The size of the key.
     * @return [CertificateGenerationResult] if successful, null otherwise.
     */
    fun generateClientCertificate(
        alias: String,
        subjectDN: String,
        keyAlgorithm: String,
        keySize: Int
    ): CertificateGenerationResult?

    /**
     * Retrieves a stored client certificate and its private key.
     * @param alias The alias of the stored certificate.
     * @return [CertificateGenerationResult] if found, null otherwise.
     */
    fun getClientCertificate(alias: String): CertificateGenerationResult?

    /**
     * Deletes a client certificate and its private key from secure storage.
     * @param alias The alias of the certificate to delete.
     * @return True if deletion was successful, false otherwise.
     */
    fun deleteClientCertificate(alias: String): Boolean
}
