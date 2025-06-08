package com.example.sshinjector.ui.connection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.sshinjector.db.AppDatabase
import com.example.sshinjector.db.SSHProfileDao
import com.example.sshinjector.model.SSHProfile
import com.example.sshinjector.ssh.SshConnectionListener
import com.example.sshinjector.ssh.SshConnectionManager
import com.example.sshinjector.ssh.SshStatus
import com.example.sshinjector.security.EncryptionHelper // For decrypting password/key
import kotlinx.coroutines.launch
import java.util.LinkedList // For logs

class ConnectionViewModel(application: Application) : AndroidViewModel(application), SshConnectionListener {

    private val sshProfileDao: SSHProfileDao
    private val sshConnectionManager: SshConnectionManager

    val allProfiles: LiveData<List<SSHProfile>>

    private val _selectedProfile = MutableLiveData<SSHProfile?>()
    val selectedProfile: LiveData<SSHProfile?> = _selectedProfile

    private val _payload = MutableLiveData<String>("")
    val payload: LiveData<String> = _payload

    private val _connectionStatus = MutableLiveData<SshStatus>(SshStatus.DISCONNECTED)
    val connectionStatus: LiveData<SshStatus> = _connectionStatus

    private val _logs = MutableLiveData<LinkedList<String>>(LinkedList())
    val logs: LiveData<LinkedList<String>> = _logs
    private val MAX_LOG_LINES = 200

    init {
        val database = AppDatabase.getDatabase(application)
        sshProfileDao = database.sshProfileDao()
        allProfiles = sshProfileDao.getAllProfiles()
        sshConnectionManager = SshConnectionManager(this)
    }

    fun setSelectedProfile(profile: SSHProfile?) {
        _selectedProfile.value = profile
        if (profile == null) { // If profile is deselected, ensure we are disconnected
            if (sshConnectionManager.isConnected()) {
                disconnect()
            }
        }
    }

    fun setPayload(newPayload: String) {
        _payload.value = newPayload
    }

    fun connectOrDisconnect() {
        if (sshConnectionManager.isConnected()) {
            disconnect()
        } else {
            val profile = _selectedProfile.value
            if (profile == null) {
                addLog("Error: No profile selected.")
                return
            }
            // Decrypt password/key before connecting
            // This assumes profile.id is stable and was used for encryption alias suffix
            val decryptedCredential = try {
                EncryptionHelper.decryptData(profile.id.toString(), profile.encryptedCredentialP1, profile.encryptedCredentialP2)
            } catch (e: Exception) {
                addLog("Error: Failed to decrypt credentials for profile '${profile.profileName}'. ${e.message}")
                _connectionStatus.postValue(SshStatus.ERROR) // Post value if from background
                return
            }

            if (decryptedCredential.isBlank() && !profile.isKeyBasedAuth) { // Key content can be complex, don't check blank for it here
                 addLog("Error: Decrypted password is blank for profile '${profile.profileName}'. Cannot connect.")
                _connectionStatus.postValue(SshStatus.ERROR)
                return
            }

            sshConnectionManager.connect(profile, decryptedCredential)
        }
    }

    private fun disconnect() {
        sshConnectionManager.disconnect()
    }

    fun sendPayloadCommands() {
        if (!sshConnectionManager.isConnected()) {
            addLog("Error: Not connected. Cannot send payload.")
            return
        }
        val payloadToSend = _payload.value
        if (payloadToSend.isNullOrBlank()) {
            addLog("Warning: Payload is empty.")
            // Optionally send an empty line or do nothing
            // sshConnectionManager.sendCommand("") // Send a newline to potentially get a prompt
            return
        }

        // Assuming payload is a series of commands, split by newline
        payloadToSend.lines().forEach { line ->
            if (line.isNotBlank()) { // Send non-empty lines
                sshConnectionManager.sendCommand(line)
            }
        }
    }

    private fun addLog(logMessage: String) {
        // PostValue if called from SshConnectionListener (potentially background thread)
        // If called from ViewModel methods (main thread), setValue is fine.
        // For consistency and safety with listener:
        _logs.value?.let { currentLogs ->
            // Check if on main thread for setValue, otherwise use postValue
            // For simplicity, always using postValue from listener context is safer.
            // Here, this is a private fun, let's assume it's called from a context that needs postValue for logs.
            val newLogs = LinkedList(currentLogs)
            newLogs.add(logMessage)
            while (newLogs.size > MAX_LOG_LINES) {
                newLogs.poll()
            }
            _logs.postValue(newLogs)
        } ?: _logs.postValue(LinkedList<String>().apply { add(logMessage) })
    }

    // SshConnectionListener Implementation
    override fun onLogOutput(log: String) {
        addLog(log)
    }

    override fun onStatusChanged(status: SshStatus) {
        _connectionStatus.postValue(status) // Ensure updates from listener are on main thread via postValue
        addLog("Status changed: $status")
    }

    override fun onSessionConnected() {
        // Optionally send initial payload/commands if configured to do so
        // For now, user clicks a button to send payload.
        // addLog("Session fully connected. Ready for commands.")
    }

    override fun onSessionDisconnected() {
        // Handled by onStatusChanged(SshStatus.DISCONNECTED)
    }

    override fun onError(errorMessage: String) {
        addLog("SSH Error: $errorMessage")
        _connectionStatus.postValue(SshStatus.ERROR)
    }

    override fun onCleared() {
        super.onCleared()
        sshConnectionManager.destroy() // Clean up SshConnectionManager resources
        addLog("ConnectionViewModel cleared. SSH Manager destroyed.")
    }
}
