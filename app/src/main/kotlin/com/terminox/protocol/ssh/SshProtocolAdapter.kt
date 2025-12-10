/**
     * Authenticates with a client certificate and opens shell channel.
     */
    suspend fun authenticateWithClientCertificate(
        sessionId: String,
        alias: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            val certData = certificateRepository.getClientCertificate(alias)
                ?: return@withContext Result.failure(IllegalArgumentException("Client certificate with alias '$alias' not found."))

            val keyPair = KeyPair(certData.certificate.publicKey, certData.privateKey)
            holder.clientSession.addPublicKeyIdentity(keyPair)
            holder.clientSession.auth().verify(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            openShellChannel(sessionId, holder)
        } catch (e: Exception) {
            Log.e(TAG, "Client certificate authentication failed", e)
            Result.failure(e)
        }
    }