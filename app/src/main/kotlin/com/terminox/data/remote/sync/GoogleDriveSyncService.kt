package com.terminox.data.remote.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive sync service implementation.
 * Uses Google Drive API with app-specific folder for sync data.
 */
@Singleton
class GoogleDriveSyncService @Inject constructor(
    @ApplicationContext private val context: Context
) : CloudSyncService {

    private var driveService: Drive? = null
    private var googleSignInClient: GoogleSignInClient? = null

    companion object {
        private const val TAG = "GoogleDriveSyncService"
        private const val APP_FOLDER_NAME = "Terminox"
        private const val SYNC_FILENAME = "terminox-sync.enc"
        private const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        private const val MIME_TYPE_BINARY = "application/octet-stream"
    }

    init {
        initializeGoogleSignIn()
    }

    private fun initializeGoogleSignIn() {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, signInOptions)
    }

    /**
     * Get the Google Sign-In client for starting the sign-in flow.
     */
    fun getSignInClient(): GoogleSignInClient? = googleSignInClient

    /**
     * Handle sign-in result from the activity.
     */
    fun handleSignInResult(account: GoogleSignInAccount): Result<Boolean> {
        return try {
            initializeDriveService(account)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Drive service", e)
            Result.failure(
                SyncException(
                    "Failed to initialize Google Drive: ${e.message}",
                    cause = e,
                    errorCode = SyncErrorCode.NOT_AUTHENTICATED
                )
            )
        }
    }

    private fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Terminox")
            .build()
    }

    override suspend fun isAuthenticated(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(context)
                if (account != null && driveService == null) {
                    initializeDriveService(account)
                }
                driveService != null && account != null
            } catch (e: Exception) {
                Log.e(TAG, "Auth check failed", e)
                false
            }
        }
    }

    override suspend fun authenticate(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(context)
                if (account != null) {
                    initializeDriveService(account)

                    // Verify access by getting app folder
                    getOrCreateAppFolder()
                    Result.success(true)
                } else {
                    // Need to trigger sign-in flow from UI
                    Result.failure(
                        SyncException(
                            "Google Sign-In required",
                            errorCode = SyncErrorCode.NOT_AUTHENTICATED
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authentication failed", e)
                Result.failure(
                    SyncException(
                        "Google Drive authentication failed: ${e.message}",
                        cause = e,
                        errorCode = SyncErrorCode.NOT_AUTHENTICATED
                    )
                )
            }
        }
    }

    override suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                googleSignInClient?.signOut()
                driveService = null
            } catch (e: Exception) {
                Log.e(TAG, "Sign out failed", e)
            }
        }
    }

    override suspend fun upload(encryptedData: ByteArray): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(
                    SyncException("Not authenticated", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )

                val folderId = getOrCreateAppFolder()
                val existingFileId = findSyncFile(folderId)

                val fileMetadata = File().apply {
                    name = SYNC_FILENAME
                    mimeType = MIME_TYPE_BINARY
                    if (existingFileId == null) {
                        parents = listOf(folderId)
                    }
                }

                val content = ByteArrayContent(MIME_TYPE_BINARY, encryptedData)

                val uploadedFile = if (existingFileId != null) {
                    // Update existing file
                    drive.files().update(existingFileId, fileMetadata, content)
                        .execute()
                } else {
                    // Create new file
                    drive.files().create(fileMetadata, content)
                        .setFields("id,modifiedTime")
                        .execute()
                }

                val timestamp = uploadedFile.modifiedTime?.value ?: System.currentTimeMillis()
                Result.success(timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                Result.failure(
                    SyncException(
                        "Failed to upload sync data: ${e.message}",
                        cause = e,
                        errorCode = mapDriveError(e)
                    )
                )
            }
        }
    }

    override suspend fun download(): Result<ByteArray?> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(
                    SyncException("Not authenticated", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )

                val folderId = getOrCreateAppFolder()
                val fileId = findSyncFile(folderId)
                    ?: return@withContext Result.success(null)

                val outputStream = ByteArrayOutputStream()
                drive.files().get(fileId)
                    .executeMediaAndDownloadTo(outputStream)

                Result.success(outputStream.toByteArray())
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                Result.failure(
                    SyncException(
                        "Failed to download sync data: ${e.message}",
                        cause = e,
                        errorCode = mapDriveError(e)
                    )
                )
            }
        }
    }

    override suspend fun getRemoteTimestamp(): Result<Long?> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(
                    SyncException("Not authenticated", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )

                val folderId = getOrCreateAppFolder()
                val fileId = findSyncFile(folderId)
                    ?: return@withContext Result.success(null)

                val file = drive.files().get(fileId)
                    .setFields("modifiedTime")
                    .execute()

                Result.success(file.modifiedTime?.value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get remote timestamp", e)
                Result.failure(
                    SyncException(
                        "Failed to get remote timestamp: ${e.message}",
                        cause = e,
                        errorCode = mapDriveError(e)
                    )
                )
            }
        }
    }

    override suspend fun deleteRemoteData(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(
                    SyncException("Not authenticated", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )

                val folderId = getOrCreateAppFolder()
                val fileId = findSyncFile(folderId)

                if (fileId != null) {
                    drive.files().delete(fileId).execute()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed", e)
                Result.failure(
                    SyncException(
                        "Failed to delete sync data: ${e.message}",
                        cause = e,
                        errorCode = mapDriveError(e)
                    )
                )
            }
        }
    }

    override fun getProviderName(): String = "Google Drive"

    override fun needsSetup(): Boolean = driveService == null

    // Helper methods

    private fun getOrCreateAppFolder(): String {
        val drive = driveService ?: throw SyncException(
            "Not authenticated",
            errorCode = SyncErrorCode.NOT_AUTHENTICATED
        )

        // Search for existing folder
        val query = "name = '$APP_FOLDER_NAME' and mimeType = '$MIME_TYPE_FOLDER' and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()

        if (result.files.isNotEmpty()) {
            return result.files[0].id
        }

        // Create folder
        val folderMetadata = File().apply {
            name = APP_FOLDER_NAME
            mimeType = MIME_TYPE_FOLDER
        }

        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()

        return folder.id
    }

    private fun findSyncFile(folderId: String): String? {
        val drive = driveService ?: return null

        val query = "name = '$SYNC_FILENAME' and '$folderId' in parents and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()

        return result.files.firstOrNull()?.id
    }

    private fun mapDriveError(e: Exception): SyncErrorCode {
        val message = e.message?.lowercase() ?: ""
        return when {
            "unauthorized" in message || "401" in message -> SyncErrorCode.NOT_AUTHENTICATED
            "forbidden" in message || "403" in message -> SyncErrorCode.PERMISSION_DENIED
            "not found" in message || "404" in message -> SyncErrorCode.NOT_FOUND
            "quota" in message || "storage" in message -> SyncErrorCode.QUOTA_EXCEEDED
            "network" in message || "connection" in message -> SyncErrorCode.NETWORK_ERROR
            else -> SyncErrorCode.SERVER_ERROR
        }
    }
}
