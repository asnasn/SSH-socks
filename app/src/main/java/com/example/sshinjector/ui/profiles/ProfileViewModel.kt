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
        credential: String, // Holds actual password or key content
        keyAlias: String?,   // The user-defined name for the key, if key auth
        isNewProfile: Boolean,
        profileId: Long = 0L
    ) {
        if (profileName.isBlank() || host.isBlank() || portStr.isBlank() || username.isBlank()) {
            _eventMessage.value = "Profile name, host, port, and username are required."
            _saveEvent.value = ProfileSaveEvent.Error("Profile name, host, port, and username are required.")
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            _eventMessage.value = "Invalid port number."
            _saveEvent.value = ProfileSaveEvent.Error("Invalid port number.")
            return
        }

        if (authMethodIsKey) {
            // For new key-based profiles, credential (key content) must not be blank.
            // For existing key-based profiles, if credential is blank, it means "don't change the key".
            if (isNewProfile && credential.isBlank()) {
                _eventMessage.value = "Private key content cannot be empty for a new key-based profile."
                _saveEvent.value = ProfileSaveEvent.Error("Private key content cannot be empty for new key profile.")
                return
            }
        } else { // Password auth
            if (isNewProfile && credential.isBlank()) {
                 _eventMessage.value = "Password cannot be empty for a new profile using password authentication."
                _saveEvent.value = ProfileSaveEvent.Error("Password cannot be empty for new profile (password auth).")
                return
            }
            // If editing an existing profile with password auth, and credential (password) is blank,
            // it means "do not change the password". This case is handled below.
        }

        viewModelScope.launch {
            try {
                val existingProfile = if (!isNewProfile) sshProfileDao.getProfileByIdSync(profileId) else null

                if (!isNewProfile && existingProfile == null) {
                    _eventMessage.value = "Error: Profile to update not found."
                    _saveEvent.value = ProfileSaveEvent.Error("Profile to update not found (ID: $profileId).")
                    return@launch
                }

                // Determine if credentials need to be re-encrypted
                val (finalEncryptedData, finalIv) = if (credential.isBlank() && !isNewProfile) {
                    // Don't change credential if it's blank for an existing profile
                    if (existingProfile!!.isKeyBasedAuth != authMethodIsKey) {
                        // Auth method changed, but no new credential provided. This is an invalid state.
                        // Or, if was password and switched to key, new key is required.
                        // If was key and switched to password, new password is required.
                         _eventMessage.value = "Authentication method changed, but new credential (password/key) was not provided."
                        _saveEvent.value = ProfileSaveEvent.Error("Auth method changed, new credential required.")
                        return@launch
                    }
                    Pair(existingProfile.encryptedCredentialP1, existingProfile.encryptedCredentialP2)
                } else {
                    // New profile, or existing profile with new credential provided.
                    // Need an ID for encryption. For new profiles, insert a temporary one first.
                    val idToUseForEncryption = if (isNewProfile) {
                        val tempProfileForId = SSHProfile(
                            profileName = profileName, host = host, port = port, username = username,
                            encryptedCredentialP1 = ByteArray(0), encryptedCredentialP2 = ByteArray(0),
                            isKeyBasedAuth = authMethodIsKey,
                            keyAlias = if (authMethodIsKey) keyAlias else null
                        )
                        sshProfileDao.insert(tempProfileForId)
                    } else {
                        profileId
                    }
                    EncryptionHelper.encryptData(idToUseForEncryption.toString(), credential)
                }

                val finalProfile = SSHProfile(
                    id = if (isNewProfile) (existingProfile?.id ?: profileId) else profileId, // This logic needs to be right for ID
                    profileName = profileName, host = host, port = port, username = username,
                    encryptedCredentialP1 = finalEncryptedData, encryptedCredentialP2 = finalIv,
                    isKeyBasedAuth = authMethodIsKey,
                    keyAlias = if (authMethodIsKey) keyAlias else null
                )

                // Correcting the ID assignment for new profiles after temp insertion
                val profileToSave = if (isNewProfile && existingProfile == null) {
                     // This means a temp profile was inserted to get an ID for encryption.
                     // The 'finalEncryptedData' and 'finalIv' were based on this new ID.
                     // So, 'finalProfile' should be updated with this new ID if it's not already set.
                     // The 'idToUseForEncryption' holds this new ID.
                    finalProfile.copy(id = sshProfileDao.insert(finalProfile.copy(id=0))) // Re-insert to get the ID to be absolutely sure, or use the id from temp insert
                                                                      // This is getting complicated. Let's simplify the ID generation for new profiles.
                } else {
                    finalProfile
                }


                // Simplified ID handling for new profiles:
                // 1. Create profile object with all data EXCEPT encrypted parts and ID.
                // 2. Insert it, get the generated ID.
                // 3. Encrypt credentials using this ID.
                // 4. Update the profile with encrypted data.

                if (isNewProfile) {
                    var newProfile = SSHProfile(
                        profileName = profileName, host = host, port = port, username = username,
                        encryptedCredentialP1 = ByteArray(0), // Temp
                        encryptedCredentialP2 = ByteArray(0), // Temp
                        isKeyBasedAuth = authMethodIsKey,
                        keyAlias = if (authMethodIsKey) keyAlias else null
                        // ID will be auto-generated
                    )
                    val generatedId = sshProfileDao.insert(newProfile)

                    // Now encrypt with the generated ID
                    val (encryptedData, iv) = EncryptionHelper.encryptData(generatedId.toString(), credential)
                    newProfile = newProfile.copy(
                        id = generatedId,
                        encryptedCredentialP1 = encryptedData,
                        encryptedCredentialP2 = iv
                    )
                    sshProfileDao.update(newProfile) // Update with encrypted data
                     _eventMessage.value = "Profile saved: ${newProfile.profileName}"
                } else { // Existing profile
                     val profileToUpdate = existingProfile!!.copy(
                        profileName = profileName,
                        host = host,
                        port = port,
                        username = username,
                        isKeyBasedAuth = authMethodIsKey, // User might change auth type
                        keyAlias = if (authMethodIsKey) keyAlias else null,
                        encryptedCredentialP1 = finalEncryptedData, // Use potentially unchanged or new credentials
                        encryptedCredentialP2 = finalIv
                    )
                    sshProfileDao.update(profileToUpdate)
                    _eventMessage.value = "Profile updated: ${profileToUpdate.profileName}"
                }
                _saveEvent.value = ProfileSaveEvent.Success
            } catch (e: Exception) {
                _eventMessage.value = "Error saving profile: ${e.message}"
                _saveEvent.value = ProfileSaveEvent.Error("Error saving profile: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun loadProfile(id: Long) { // Changed to Long
        viewModelScope.launch {
            _selectedProfile.value = sshProfileDao.getProfileByIdSync(id)
        }
    }

    fun getDecryptedPassword(profile: SSHProfile): String? {
        if (profile.isKeyBasedAuth) return null
        if (profile.id == 0L) {
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
