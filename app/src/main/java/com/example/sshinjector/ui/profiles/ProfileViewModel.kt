package com.example.sshinjector.ui.profiles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.sshinjector.db.AppDatabase
import com.example.sshinjector.db.SSHProfileDao
import com.example.sshinjector.model.SSHProfile
import com.example.sshinjector.security.EncryptionHelper
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val sshProfileDao: SSHProfileDao
    val allProfiles: LiveData<List<SSHProfile>>

    private val _selectedProfile = MutableLiveData<SSHProfile?>()
    val selectedProfile: LiveData<SSHProfile?> = _selectedProfile

    private val _eventMessage = MutableLiveData<String?>()
    val eventMessage: LiveData<String?> = _eventMessage

    sealed class ProfileSaveEvent {
        object Success : ProfileSaveEvent()
        data class Error(val message: String) : ProfileSaveEvent()
    }
    private val _saveEvent = MutableLiveData<ProfileSaveEvent?>()
    val saveEvent: LiveData<ProfileSaveEvent?> = _saveEvent

    init {
        val database = AppDatabase.getDatabase(application)
        sshProfileDao = database.sshProfileDao()
        allProfiles = sshProfileDao.getAllProfiles()
    }

    fun insertOrUpdateProfile(
        profileName: String,
        host: String,
        portStr: String,
        username: String,
        authMethodIsKey: Boolean,
        passwordOrKeyAlias: String,
        isNewProfile: Boolean,
        profileId: Long = 0L // Changed to Long
    ) {
        if (profileName.isBlank() || host.isBlank() || portStr.isBlank() || username.isBlank() || passwordOrKeyAlias.isBlank()) {
            _eventMessage.value = "All required fields must be filled."
            _saveEvent.value = ProfileSaveEvent.Error("All required fields must be filled.")
            return
        }
        val port = portStr.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            _eventMessage.value = "Invalid port number."
            _saveEvent.value = ProfileSaveEvent.Error("Invalid port number.")
            return
        }

        viewModelScope.launch {
            try {
                if (isNewProfile) {
                    var tempProfile = SSHProfile(
                        profileName = profileName, host = host, port = port, username = username,
                        encryptedCredentialP1 = ByteArray(0), // Placeholder for encryption
                        encryptedCredentialP2 = ByteArray(0), // Placeholder for IV
                        isKeyBasedAuth = authMethodIsKey,
                        keyAlias = if (authMethodIsKey) passwordOrKeyAlias else null
                    )
                    val newId = sshProfileDao.insert(tempProfile)

                    // Now encrypt with the stable ID
                    val (encryptedCredential, iv) = EncryptionHelper.encryptData(newId.toString(), passwordOrKeyAlias)
                    val finalProfile = tempProfile.copy(
                        id = newId, // ID is Long
                        encryptedCredentialP1 = encryptedCredential,
                        encryptedCredentialP2 = iv
                    )
                    sshProfileDao.update(finalProfile)
                    _eventMessage.value = "Profile saved: ${finalProfile.profileName}"
                } else { // Existing profile
                    val (encryptedCredential, iv) = EncryptionHelper.encryptData(profileId.toString(), passwordOrKeyAlias)
                    val updatedProfile = SSHProfile(
                        id = profileId,
                        profileName = profileName, host = host, port = port, username = username,
                        encryptedCredentialP1 = encryptedCredential, encryptedCredentialP2 = iv,
                        isKeyBasedAuth = authMethodIsKey,
                        keyAlias = if (authMethodIsKey) passwordOrKeyAlias else null
                    )
                    sshProfileDao.update(updatedProfile)
                    _eventMessage.value = "Profile updated: ${updatedProfile.profileName}"
                }
                _saveEvent.value = ProfileSaveEvent.Success
            } catch (e: Exception) {
                _eventMessage.value = "Error saving profile: ${e.message}"
                _saveEvent.value = ProfileSaveEvent.Error("Error saving profile: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun deleteProfile(profile: SSHProfile) {
        viewModelScope.launch {
            try {
                sshProfileDao.delete(profile)
                _eventMessage.value = "Profile '${profile.profileName}' deleted."
                // Consider deleting Keystore alias via EncryptionHelper if it's no longer needed
            } catch (e: Exception) {
                _eventMessage.value = "Error deleting profile: ${e.message}"
            }
        }
    }

    fun loadProfile(id: Long) { // Changed to Long
        viewModelScope.launch {
            _selectedProfile.value = sshProfileDao.getProfileByIdSync(id)
        }
    }

    fun getDecryptedPassword(profile: SSHProfile): String? {
        if (profile.isKeyBasedAuth) return null // Or return key alias if that's useful
        if (profile.id == 0L) { // Should not happen if saved correctly
             _eventMessage.value = "Cannot decrypt password for unsaved profile."
            return null
        }
        return try {
            EncryptionHelper.decryptData(profile.id.toString(), profile.encryptedCredentialP1, profile.encryptedCredentialP2)
        } catch (e: Exception) {
            e.printStackTrace()
            _eventMessage.value = "Decryption failed. The key might have changed or data is corrupt."
            null
        }
    }

    fun onEventMessageShown() {
        _eventMessage.value = null
    }

    fun onSaveEventHandled() {
        _saveEvent.value = null
    }
}
