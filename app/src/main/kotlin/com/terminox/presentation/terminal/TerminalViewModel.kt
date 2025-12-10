/**
     * Authenticates using a client certificate.
     */
    private fun authenticateWithClientCertificate(sessionId: String, alias: String, connection: Connection) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting client certificate authentication for alias: $alias")

                // Authenticate with the SSH adapter
                val result = sshAdapter.authenticateWithClientCertificate(sessionId, alias)

                result.fold(
                    onSuccess = {
                        Log.d(TAG, "Client certificate authentication successful")

                        // Log successful authentication
                        auditLogRepository.logConnectionSuccess(
                            connectionId = connection.id,
                            connectionName = connection.name,
                            host = connection.host,
                            port = connection.port,
                            username = connection.username,
                            authMethod = "client-certificate",
                            keyFingerprint = "alias:$alias" // Using alias as a stand-in for fingerprint
                        )

                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.CONNECTED,
                                terminalState = currentSession?.emulator?.state?.value ?: TerminalState()
                            )
                        }

                        // Update last connected timestamp
                        connectionRepository.updateLastConnected(
                            connection.id,
                            System.currentTimeMillis()
                        )

                        // Start collecting output
                        startOutputCollection(sessionId)
                        updateSessionList()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Client certificate authentication failed", error)

                        // Log authentication failure
                        auditLogRepository.logConnectionFailed(
                            connectionId = connection.id,
                            connectionName = connection.name,
                            host = connection.host,
                            port = connection.port,
                            username = connection.username,
                            authMethod = "client-certificate",
                            errorMessage = "Client certificate auth failed: ${error.message}"
                        )

                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.ERROR,
                                error = "Client certificate auth failed: ${error.message}"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Client certificate authentication error", e)
                _uiState.update {
                    it.copy(
                        sessionState = SessionState.ERROR,
                        error = "Client certificate auth error: ${e.message}"
                    )
                }
            }
        }
    }