package com.example.sshinjector.ssh

interface SshConnectionListener {
    fun onLogOutput(log: String)
    fun onStatusChanged(status: SshStatus)
    fun onSessionConnected() // Specifically when session is fully usable
    fun onSessionDisconnected()
    fun onError(errorMessage: String)
}
