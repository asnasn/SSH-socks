package com.example.sshinjector.ssh

import com.example.sshinjector.model.SSHProfile
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Properties
import java.io.IOException // Import IOException

class SshConnectionManager(private val listener: SshConnectionListener) {

    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var sessionScope = CoroutineScope(Dispatchers.IO + Job())

    // For writing to the shell's input (commands from UI)
    private var shellPipedOutForCommands: PipedOutputStream? = null

    // Internal streams for shell output processing
    private var internalShellOutputReader: PipedInputStream? = null
    private var internalShellOutputWriter: PipedOutputStream? = null
    private var outputReadingJob: Job? = null


    init {
        JSch.setLogger(object : com.jcraft.jsch.Logger {
            override fun log(level: Int, message: String) {
                listener.onLogOutput("[JSch-$level]: $message")
            }
            override fun isEnabled(level: Int): Boolean = true
        })
    }

    fun connect(profile: SSHProfile, decryptedCredential: String) {
        if (session?.isConnected == true || shellChannel?.isConnected == true) {
            listener.onLogOutput("Already connected or connecting.")
            return
        }

        // Ensure previous connection resources are cleaned up, especially the scope and jobs
        cleanupConnectionResources() // Call a new method to ensure old resources are fully reset
        sessionScope = CoroutineScope(Dispatchers.IO + Job()) // Recreate the scope

        sessionScope.launch {
            listener.onStatusChanged(SshStatus.CONNECTING)
            listener.onLogOutput("Attempting to connect to ${profile.host}:${profile.port} as ${profile.username}...")

            try {
                val jsch = JSch()

                if (profile.isKeyBasedAuth) {
                    listener.onLogOutput("Using key-based authentication.")
                    val identityName = profile.profileName ?: "ssh_identity"
                    try {
                        jsch.addIdentity(
                            identityName,
                            decryptedCredential.toByteArray(Charsets.UTF_8),
                            null,
                            null
                        )
                        listener.onLogOutput("Identity '${identityName}' added.")
                    } catch (e: Exception) {
                        listener.onError("Failed to add SSH key: ${e.message}")
                        e.printStackTrace()
                        // No explicit disconnect() here as full cleanup is at the end of try-catch
                        throw e // Propagate to outer catch for full cleanup
                    }
                }

                session = jsch.getSession(profile.username, profile.host, profile.port)

                if (!profile.isKeyBasedAuth) {
                    listener.onLogOutput("Using password-based authentication.")
                    session?.setPassword(decryptedCredential)
                }

                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                config["PreferredAuthentications"] = if (profile.isKeyBasedAuth) "publickey,password,keyboard-interactive" else "password,publickey,keyboard-interactive"
                session?.setConfig(config)
                session?.setConfig("ConnectTimeout", "10000")

                session?.connect(15000)

                if (session?.isConnected == true) {
                    listener.onLogOutput("SSH Session Connected.")
                    listener.onStatusChanged(SshStatus.AUTHENTICATED)

                    shellChannel = session?.openChannel("shell") as? ChannelShell
                    if (shellChannel == null) {
                        throw Exception("Failed to open shell channel.")
                    }

                    // Setup internal pipes for shell output
                    internalShellOutputReader = PipedInputStream(1024 * 8) // Buffer size for pipe
                    internalShellOutputWriter = PipedOutputStream(internalShellOutputReader)
                    shellChannel!!.outputStream = internalShellOutputWriter // Shell stdout -> internalShellOutputWriter

                    // Setup pipes for shell input (commands from UI)
                    val pipedInForShellCommands = PipedInputStream() // Shell stdin reads from this
                    shellPipedOutForCommands = PipedOutputStream(pipedInForShellCommands) // UI writes commands here
                    shellChannel!!.inputStream = pipedInForShellCommands

                    // shellChannel!!.setPtyType("xterm")

                    shellChannel!!.connect(5000)

                    if (shellChannel!!.isConnected) {
                        listener.onLogOutput("Shell channel opened.")
                        listener.onStatusChanged(SshStatus.CHANNEL_OPEN)
                        startShellOutputReader() // Launch coroutine to read shell output
                        listener.onSessionConnected()
                    } else {
                        throw Exception("Failed to connect shell channel.")
                    }
                } else {
                    throw Exception("Failed to establish SSH session.")
                }

            } catch (e: Exception) {
                listener.onError("Connection failed: ${e.message}")
                listener.onLogOutput("Error: ${e.localizedMessage}")
                // e.printStackTrace() // Printing stack trace directly might be too verbose for UI log
                disconnect() // Clean up on any exception during connect
            }
        }
    }

    private fun startShellOutputReader() {
        outputReadingJob = sessionScope.launch(Dispatchers.IO) { // Ensure this is on an IO dispatcher
            val buffer = ByteArray(1024)
            var bytesRead: Int
            try {
                while (isActive && internalShellOutputReader != null &&
                       (internalShellOutputReader!!.read(buffer).also { bytesRead = it }) != -1) {
                    val output = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    listener.onLogOutput(output) // Or a new onTerminalData(output) method
                }
            } catch (e: IOException) {
                if (isActive) { // Only log error if the scope is still active (i.e., not a planned disconnect)
                    listener.onLogOutput("Shell output stream closed or error: ${e.message}")
                }
            } finally {
                listener.onLogOutput("Shell output reader finished.")
                // No need to close internalShellOutputReader here, disconnect() will handle it.
            }
        }
    }

    fun sendCommand(command: String) {
        if (shellChannel?.isConnected == true && shellPipedOutForCommands != null) {
            sessionScope.launch(Dispatchers.IO) { // Ensure command sending is also on IO dispatcher
                try {
                    val commandWithNewline = if (command.endsWith("\n")) command else "$command\n"
                    shellPipedOutForCommands!!.write(commandWithNewline.toByteArray(Charsets.UTF_8))
                    shellPipedOutForCommands!!.flush()
                    // Avoid logging "Sent:" here, let the shell echo confirm it via onLogOutput
                    // listener.onLogOutput("Sent: ${command.trim()}")
                } catch (e: Exception) {
                    listener.onError("Error sending command: ${e.message}")
                }
            }
        } else {
            listener.onError("Not connected. Cannot send command.")
        }
    }

    private fun cleanupConnectionResources() {
        outputReadingJob?.cancel() // Cancel previous reading job
        outputReadingJob = null

        try {
            shellPipedOutForCommands?.close()
            // PipedInputStream connected to shellPipedOutForCommands is managed by shellChannel
        } catch (e: IOException) { /* ignore */ }
        shellPipedOutForCommands = null

        try {
            internalShellOutputWriter?.close()
        } catch (e: IOException) { /* ignore */ }
        internalShellOutputWriter = null

        try {
            internalShellOutputReader?.close()
        } catch (e: IOException) { /* ignore */ }
        internalShellOutputReader = null

        shellChannel?.disconnect()
        shellChannel = null
        session?.disconnect()
        session = null

        // sessionScope is cancelled and recreated in connect() or destroy()
    }

    fun disconnect() {
        listener.onStatusChanged(SshStatus.DISCONNECTING)
        listener.onLogOutput("Disconnecting...")

        sessionScope.launch(Dispatchers.IO) { // Perform disconnect operations on IO dispatcher
            cleanupConnectionResources()
            // sessionScope.cancel() // Cancelling the scope that runs this coroutine can be tricky.
                                 // Let destroy() or new connect() handle final scope cancellation.

            // Notify listeners on the main thread or appropriate context if necessary
            // For now, direct call assuming listener can handle calls from any thread, or is main-safe.
            listener.onLogOutput("Disconnected.")
            listener.onStatusChanged(SshStatus.DISCONNECTED)
            listener.onSessionDisconnected()
        }
        // If disconnect() is called from outside a sessionScope coroutine, then cancelling sessionScope here is fine.
        // If called from within, it's better to let the calling coroutine complete.
        // For simplicity, assume it's called from UI thread or a context that allows this.
        // However, the `cleanupConnectionResources` already does most of the work.
        // The scope itself will be cancelled by destroy() or before a new connect().
    }

    fun isConnected(): Boolean {
        return session?.isConnected == true && shellChannel?.isConnected == true && outputReadingJob?.isActive == true
    }

    fun destroy() {
        listener.onLogOutput("Destroying SshConnectionManager...")
        disconnect() // Ensure graceful disconnect
        sessionScope.cancel() // Cancel all coroutines
    }
}
