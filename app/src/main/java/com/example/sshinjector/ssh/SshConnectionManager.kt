package com.example.sshinjector.ssh

import com.example.sshinjector.model.SSHProfile
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Properties

class SshConnectionManager(private val listener: SshConnectionListener) {

    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var sessionScope = CoroutineScope(Dispatchers.IO + Job())

    private var out: PipedOutputStream? = null // Data to SSH server
    val terminalInputStream: InputStream by lazy { PipedInputStream(out) } // Data from SSH server (shell output)

    private var shellIn: PipedInputStream? = null // To write to shell's input
    private var shellOut: PipedOutputStream? = null // To connect to shell's input stream

    init {
        JSch.setLogger(object : com.jcraft.jsch.Logger {
            override fun log(level: Int, message: String) {
                listener.onLogOutput("[JSch-$level]: $message")
            }
            override fun isEnabled(level: Int): Boolean = true
        })
    }

    fun connect(profile: SSHProfile, decryptedPasswordOrKeyPath: String) {
        if (session?.isConnected == true || shellChannel?.isConnected == true) {
            listener.onLogOutput("Already connected or connecting.")
            return
        }

        sessionScope.cancel() // Cancel any previous scope
        sessionScope = CoroutineScope(Dispatchers.IO + Job())

        sessionScope.launch {
            listener.onStatusChanged(SshStatus.CONNECTING)
            listener.onLogOutput("Attempting to connect to ${profile.host}:${profile.port} as ${profile.username}...")

            try {
                val jsch = JSch()
                // TODO: Handle key-based authentication: jsch.addIdentity(keyPath, passphrase)
                // For now, assuming password authentication or key path is the password field.

                session = jsch.getSession(profile.username, profile.host, profile.port)
                session?.setPassword(decryptedPasswordOrKeyPath)

                // Important: StrictHostKeyChecking
                // For a real app, you'd want to allow user to verify host key or use known_hosts.
                // For an injector-style app, often this is set to "no".
                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                config["PreferredAuthentications"] = "password,publickey,keyboard-interactive" // Adjust as needed
                session?.setConfig(config)
                session?.setConfig("ConnectTimeout", "10000") // 10 seconds timeout


                session?.connect(15000) // 15 seconds connection timeout

                if (session?.isConnected == true) {
                    listener.onLogOutput("SSH Session Connected.")
                    listener.onStatusChanged(SshStatus.AUTHENTICATED) // Or CONNECTED if session implies full connection

                    shellChannel = session?.openChannel("shell") as? ChannelShell
                    if (shellChannel == null) {
                        throw Exception("Failed to open shell channel.")
                    }

                    // Setup PipedInput/OutputStreams for shell interaction
                    // Data from shellChannel (remote) goes to terminalInputStream (local UI)
                    val channelOut = PipedOutputStream()
                    shellChannel!!.outputStream = channelOut // shell stdout -> channelOut
                    // terminalInputStream = PipedInputStream(channelOut) // Already lazy initialized with 'out'

                    // Data from local (UI command input) goes to shellChannel's input
                    // This is for sending commands TO the shell
                    shellIn = PipedInputStream()
                    shellChannel!!.inputStream = shellIn // shell stdin <- shellIn
                    shellOut = PipedOutputStream(shellIn)


                    // For reading from the shell and forwarding to listener
                    // This PipedOutputStream is where the shell's output goes.
                    // We need to connect it to the terminalInputStream that the UI will read.
                    out = PipedOutputStream() // Data written here goes to terminalInputStream
                    shellChannel!!.outputStream = out // Redirect shell output to 'out'

                    // Set terminal type if needed
                    // shellChannel!!.setPtyType("dumb") // or "ansi", "vt100", etc.

                    shellChannel!!.connect(5000) // 5 seconds channel connect timeout

                    if (shellChannel!!.isConnected) {
                        listener.onLogOutput("Shell channel opened.")
                        listener.onStatusChanged(SshStatus.CHANNEL_OPEN)
                        listener.onSessionConnected() // Notify fully connected and ready

                        // Start a new coroutine to continuously read from the shell's output stream
                        // and forward it to the listener. This part is tricky with PipedStreams
                        // and blocking reads. A dedicated thread or non-blocking IO might be better
                        // in a full implementation.
                        // For now, the UI will be responsible for reading from terminalInputStream.
                    } else {
                        throw Exception("Failed to connect shell channel.")
                    }
                } else {
                    throw Exception("Failed to establish SSH session.")
                }

            } catch (e: Exception) {
                listener.onError("Connection failed: ${e.message}")
                listener.onLogOutput("Error: ${e.localizedMessage}")
                e.printStackTrace()
                disconnect() // Clean up
            }
        }
    }

    fun sendCommand(command: String) {
        if (shellChannel?.isConnected == true && shellOut != null) {
            sessionScope.launch {
                try {
                    // Append newline as if typing in a terminal
                    val commandWithNewline = if (command.endsWith("\n")) command else "$command\n"
                    shellOut!!.write(commandWithNewline.toByteArray(Charsets.UTF_8))
                    shellOut!!.flush()
                    listener.onLogOutput("Sent: ${command.trim()}")
                } catch (e: Exception) {
                    listener.onError("Error sending command: ${e.message}")
                    disconnect()
                }
            }
        } else {
            listener.onError("Not connected or shell output stream is null. Cannot send command.")
        }
    }

    fun disconnect() {
        listener.onStatusChanged(SshStatus.DISCONNECTING)
        listener.onLogOutput("Disconnecting...")
        try {
            shellOut?.close()
            shellIn?.close()
            out?.close() // Close the PipedOutputStream connected to terminalInputStream
            // terminalInputStream.close() // This will be closed by out.close()

            shellChannel?.disconnect()
            session?.disconnect()
        } catch (e: Exception) {
            listener.onLogOutput("Exception during disconnect: ${e.message}")
        } finally {
            shellChannel = null
            session = null
            shellOut = null
            shellIn = null
            out = null
            // terminalInputStream = null // Re-lazy init on next connect

            sessionScope.cancel() // Cancel all coroutines in this scope
            listener.onLogOutput("Disconnected.")
            listener.onStatusChanged(SshStatus.DISCONNECTED)
            listener.onSessionDisconnected()
        }
    }

    fun isConnected(): Boolean {
        return session?.isConnected == true && shellChannel?.isConnected == true
    }

    // Call this when the manager is no longer needed to clean up the coroutine scope
    fun destroy() {
        disconnect() // Ensure everything is closed
        sessionScope.cancel()
    }
}
