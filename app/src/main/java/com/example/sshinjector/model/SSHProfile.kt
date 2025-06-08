package com.example.sshinjector.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_profiles")
data class SSHProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L, // Changed to Long
    val profileName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val encryptedCredentialP1: ByteArray,
    val encryptedCredentialP2: ByteArray, // IV
    val isKeyBasedAuth: Boolean = false,
    val keyAlias: String? = null // Alias for SSH key if using key-based auth
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SSHProfile
        if (id != other.id) return false
        if (profileName != other.profileName) return false
        if (host != other.host) return false
        if (port != other.port) return false
        if (username != other.username) return false
        if (!encryptedCredentialP1.contentEquals(other.encryptedCredentialP1)) return false
        if (!encryptedCredentialP2.contentEquals(other.encryptedCredentialP2)) return false
        if (isKeyBasedAuth != other.isKeyBasedAuth) return false
        if (keyAlias != other.keyAlias) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode() // Updated for Long
        result = 31 * result + profileName.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + encryptedCredentialP1.contentHashCode()
        result = 31 * result + encryptedCredentialP2.contentHashCode()
        result = 31 * result + isKeyBasedAuth.hashCode()
        result = 31 * result + (keyAlias?.hashCode() ?: 0)
        return result
    }
}
