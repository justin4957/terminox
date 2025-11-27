package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: String,
    val publicKey: String,
    val fingerprint: String,
    val encryptedPrivateKey: ByteArray,
    val iv: ByteArray,
    val createdAt: Long,
    val requiresBiometric: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SshKeyEntity
        return id == other.id &&
                name == other.name &&
                type == other.type &&
                publicKey == other.publicKey &&
                fingerprint == other.fingerprint &&
                encryptedPrivateKey.contentEquals(other.encryptedPrivateKey) &&
                iv.contentEquals(other.iv) &&
                createdAt == other.createdAt &&
                requiresBiometric == other.requiresBiometric
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + encryptedPrivateKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + requiresBiometric.hashCode()
        return result
    }
}
