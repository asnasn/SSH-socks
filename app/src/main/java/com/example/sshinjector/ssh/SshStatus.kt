package com.example.sshinjector.ssh

enum class SshStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED, // Channel open and authenticated
    AUTHENTICATED, // Logged in, but channel not necessarily open for commands yet
    CHANNEL_OPEN, // Shell channel open
    ERROR,
    DISCONNECTING
}
