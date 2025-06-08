package com.example.sshinjector.ui.connection

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.example.sshinjector.R
import com.example.sshinjector.databinding.FragmentConnectionBinding
import com.example.sshinjector.model.SSHProfile
import com.example.sshinjector.ssh.SshStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionFragment : Fragment() {

    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConnectionViewModel by viewModels()
    private val args: ConnectionFragmentArgs by navArgs() // To receive profileId if navigated from list

    private lateinit var profileSpinnerAdapter: ArrayAdapter<String>
    private var profilesList: List<SSHProfile> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSpinner()
        setupPayloadEditText()
        setupStartStopButton()
        observeViewModel()

        binding.textviewLogs.movementMethod = ScrollingMovementMethod() // Make logs scrollable

        if (args.profileId != -1L && args.profileId != 0L) { // 0L might be default for new if not -1L
            // A profile was directly selected to connect to.
            // We need to wait for allProfiles to load, then select it.
            viewModel.allProfiles.observe(viewLifecycleOwner) { profiles ->
                if (profiles.isNotEmpty()) {
                    val profileToSelect = profiles.find { it.id == args.profileId }
                    profileToSelect?.let {
                        val position = profilesList.indexOf(it) // Use the class member profilesList
                        if (position >= 0) {
                            binding.spinnerSshProfiles.setSelection(position)
                            // Optionally auto-connect if profile passed via navArgs
                            // viewModel.connectOrDisconnect()
                        }
                    }
                }
            }
        }
    }

    private fun setupSpinner() {
        profileSpinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, mutableListOf<String>())
        profileSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSshProfiles.adapter = profileSpinnerAdapter

        binding.spinnerSshProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < profilesList.size) {
                    viewModel.setSelectedProfile(profilesList[position])
                } else if (profilesList.isEmpty()){ // Handle case where adapter might be empty initially
                    viewModel.setSelectedProfile(null)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                viewModel.setSelectedProfile(null)
            }
        }
    }

    private fun setupPayloadEditText() {
        binding.edittextPayload.addTextChangedListener { text ->
            viewModel.setPayload(text.toString())
        }
    }

    private fun setupStartStopButton() {
        binding.buttonStartStopConnection.setOnClickListener {
            val currentStatus = viewModel.connectionStatus.value
            if (currentStatus == SshStatus.CHANNEL_OPEN || currentStatus == SshStatus.CONNECTED || currentStatus == SshStatus.AUTHENTICATED) {
                 viewModel.sendPayloadCommands()
            } else if (currentStatus == SshStatus.DISCONNECTED || currentStatus == SshStatus.ERROR) {
                viewModel.connectOrDisconnect()
            }
            // If connecting or disconnecting, button is typically disabled, so no action here.
        }
    }

    private fun observeViewModel() {
        viewModel.allProfiles.observe(viewLifecycleOwner) { profiles ->
            this.profilesList = profiles ?: emptyList() // Update the class member
            val profileNames = this.profilesList.map { it.profileName }.toMutableList()
            if (profileNames.isEmpty()) {
                profileNames.add(getString(R.string.no_ssh_profiles_found)) // Add a placeholder item
                 binding.spinnerSshProfiles.isEnabled = false
            } else {
                 binding.spinnerSshProfiles.isEnabled = true
            }
            profileSpinnerAdapter.clear()
            profileSpinnerAdapter.addAll(profileNames)
            profileSpinnerAdapter.notifyDataSetChanged()

            if (args.profileId != -1L && args.profileId != 0L) {
                 val profileToSelect = this.profilesList.find { it.id == args.profileId }
                 profileToSelect?.let {
                     val position = this.profilesList.indexOf(it)
                     if (position >= 0 && binding.spinnerSshProfiles.selectedItemPosition != position) {
                         binding.spinnerSshProfiles.setSelection(position, false)
                         viewModel.setSelectedProfile(it)
                     }
                 }
            } else if (this.profilesList.isNotEmpty() && binding.spinnerSshProfiles.selectedItemPosition == AdapterView.INVALID_POSITION) {
                // If no profile passed via args, and spinner hasn't auto-selected, select first one.
                // binding.spinnerSshProfiles.setSelection(0)
                // viewModel.setSelectedProfile(this.profilesList[0])
                // Let user manually select first if nothing is passed.
            } else if (this.profilesList.isEmpty()) {
                viewModel.setSelectedProfile(null)
            }
        }

        viewModel.selectedProfile.observe(viewLifecycleOwner) { profile ->
            // Potentially update UI if needed, e.g. if profile details were shown elsewhere
        }

        viewModel.payload.observe(viewLifecycleOwner) { currentPayload ->
            if (binding.edittextPayload.text.toString() != currentPayload) {
                binding.edittextPayload.setText(currentPayload)
            }
        }

        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            val statusText = "Status: ${status.name}"
            binding.labelConnectionStatus.text = statusText
            when (status) {
                SshStatus.DISCONNECTED, SshStatus.ERROR -> {
                    binding.buttonStartStopConnection.text = getString(R.string.start_connection)
                    binding.buttonStartStopConnection.isEnabled = true
                    binding.spinnerSshProfiles.isEnabled = this.profilesList.isNotEmpty()
                    binding.edittextPayload.isEnabled = true
                }
                SshStatus.CONNECTING, SshStatus.DISCONNECTING -> {
                    binding.buttonStartStopConnection.text = if (status == SshStatus.CONNECTING) getString(R.string.status_connecting).replace("Status: ", "") else getString(R.string.status_disconnecting).replace("Status: ","")
                    binding.buttonStartStopConnection.isEnabled = false
                    binding.spinnerSshProfiles.isEnabled = false
                    binding.edittextPayload.isEnabled = false
                }
                SshStatus.AUTHENTICATED, SshStatus.CONNECTED, SshStatus.CHANNEL_OPEN -> {
                    binding.buttonStartStopConnection.text = getString(R.string.send_payload_or_stop)
                    binding.buttonStartStopConnection.isEnabled = true
                    binding.spinnerSshProfiles.isEnabled = false
                    binding.edittextPayload.isEnabled = true
                }
            }
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        viewModel.logs.observe(viewLifecycleOwner) { logsList ->
            // val timestamp = sdf.format(Date()) // Timestamping per log line is better in ViewModel's addLog
            binding.textviewLogs.text = logsList.joinToString("\n")
            binding.scrollviewLogs.post { binding.scrollviewLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
