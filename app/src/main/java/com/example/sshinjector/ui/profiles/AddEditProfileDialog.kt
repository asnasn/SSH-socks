package com.example.sshinjector.ui.profiles

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import com.example.sshinjector.R
import com.example.sshinjector.databinding.DialogAddEditProfileBinding
import com.example.sshinjector.model.SSHProfile
import java.io.BufferedReader
import java.io.InputStreamReader

class AddEditProfileDialog : DialogFragment() {

    private var _binding: DialogAddEditProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfileViewModel by navGraphViewModels(R.id.nav_graph)
    private val args: AddEditProfileDialogArgs by navArgs()

    private var isNewProfile = true
    private var importedKeyContent: String? = null // To store content of imported key

    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isNewProfile = args.profileId == -1L
        if (!isNewProfile) {
            viewModel.loadProfile(args.profileId)
        }

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleSelectedKeyFile(uri)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddEditProfileBinding.inflate(inflater, container, false)
        dialog?.setTitle(if (isNewProfile) getString(R.string.dialog_title_add_profile) else getString(R.string.dialog_title_edit_profile))
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRadioGroupListeners()
        setupButtons()

        if (!isNewProfile) {
            viewModel.selectedProfile.observe(viewLifecycleOwner) { profile ->
                if (profile != null && profile.id == args.profileId) { // Ensure it's the correct profile being observed
                    populateFields(profile)
                }
            }
        } else {
            binding.radioPasswordAuth.isChecked = true
            updateAuthFieldsVisibility(false)
        }

        viewModel.saveEvent.observe(viewLifecycleOwner) { event ->
            event?.let {
                when(it) {
                    is ProfileViewModel.ProfileSaveEvent.Success -> {
                        val message = viewModel.eventMessage.value ?: (if (isNewProfile) "Profile saved." else "Profile updated.")
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        viewModel.onEventMessageShown()
                        viewModel.onSaveEventHandled()
                        dismiss()
                    }
                    is ProfileViewModel.ProfileSaveEvent.Error -> {
                        val errorMessage = it.message.takeIf { msg -> msg.isNotEmpty() } ?: "Unknown error occurred."
                        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                        viewModel.onSaveEventHandled()
                    }
                }
            }
        }
    }

    private fun setupRadioGroupListeners() {
        binding.radiogroupAuthMethod.setOnCheckedChangeListener { _, checkedId ->
            val isKeyAuth = checkedId == R.id.radio_key_auth
            updateAuthFieldsVisibility(isKeyAuth)
        }
    }

    private fun updateAuthFieldsVisibility(isKeyAuth: Boolean) {
        binding.layoutPassword.visibility = if (isKeyAuth) View.GONE else View.VISIBLE
        binding.layoutKeyAuthFields.visibility = if (isKeyAuth) View.VISIBLE else View.GONE
        if (isKeyAuth) {
             binding.edittextPassword.text = null
             binding.edittextKeyAlias.hint = if (importedKeyContent != null) getString(R.string.imported_key_name_label) else getString(R.string.ssh_key_alias_in_keystore_optional)
        } else {
            binding.edittextKeyAlias.text = null
            importedKeyContent = null
        }
    }

    private fun setupButtons() {
        binding.buttonSave.setOnClickListener { saveProfileData() }
        binding.buttonCancel.setOnClickListener { dismiss() }
        binding.buttonImportKey.setOnClickListener {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedKeyFile(uri: Uri) {
        try {
            var fileName = "imported_key" // Default
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }

            val stringBuilder = StringBuilder()
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line).append('\n') // Preserve newlines from key file
                    }
                }
            }
            // Remove last newline if appended unnecessarily
            if (stringBuilder.isNotEmpty() && stringBuilder.last() == '\n') {
                 importedKeyContent = stringBuilder.substring(0, stringBuilder.length -1)
            } else {
                 importedKeyContent = stringBuilder.toString()
            }


            if (importedKeyContent.isNullOrBlank()){ // Check after potential trim or if file was truly empty
                Toast.makeText(context, "Failed to read key file or file is empty.", Toast.LENGTH_LONG).show()
                importedKeyContent = null
            } else {
                binding.edittextKeyAlias.setText(fileName)
                binding.edittextKeyAlias.hint = getString(R.string.imported_key_name_label)
                Toast.makeText(context, "Key '$fileName' imported. Save the profile.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            importedKeyContent = null
            Toast.makeText(context, "Error reading key file: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun populateFields(profile: SSHProfile) {
        binding.edittextProfileName.setText(profile.profileName)
        binding.edittextHost.setText(profile.host)
        binding.edittextPort.setText(profile.port.toString())
        binding.edittextUsername.setText(profile.username)
        importedKeyContent = null

        if (profile.isKeyBasedAuth) {
            binding.radioKeyAuth.isChecked = true
            binding.edittextKeyAlias.setText(profile.keyAlias ?: "")
            binding.edittextKeyAlias.hint = getString(R.string.re_import_key_if_changing)
        } else {
            binding.radioPasswordAuth.isChecked = true
            binding.edittextPassword.setText("")
            binding.edittextPassword.hint = getString(R.string.re_enter_password_if_changing)
        }
        updateAuthFieldsVisibility(profile.isKeyBasedAuth)
    }

    private fun saveProfileData() {
        val profileName = binding.edittextProfileName.text.toString().trim()
        val host = binding.edittextHost.text.toString().trim()
        val portStr = binding.edittextPort.text.toString().trim()
        val username = binding.edittextUsername.text.toString().trim()

        val isKeyAuthSelected = binding.radioKeyAuth.isChecked
        val credentialToSave: String
        val keyAliasToSave: String? = if (isKeyAuthSelected) binding.edittextKeyAlias.text.toString().trim() else null

        if (isKeyAuthSelected) {
            if (importedKeyContent != null) {
                credentialToSave = importedKeyContent!!
            } else if (!isNewProfile && viewModel.selectedProfile.value?.isKeyBasedAuth == true) {
                // Editing an existing key-based profile, but no new key was imported.
                // This means user wants to keep the old key. ViewModel needs to handle this.
                // We'll pass an empty string for credential, and ViewModel will know not to update it.
                credentialToSave = "" // Signal to ViewModel to NOT change the key
            } else if (isNewProfile && importedKeyContent == null) { // New profile, key auth, but no key.
                Toast.makeText(context, "Please import an SSH private key for key-based authentication.", Toast.LENGTH_LONG).show()
                return
            }
             else { // Should not be reached if logic above is correct, or it's a new profile with no key
                Toast.makeText(context, "Key content is missing for key-based authentication.", Toast.LENGTH_LONG).show()
                return
            }
        } else { // Password auth
            credentialToSave = binding.edittextPassword.text.toString()
            // Note: ProfileViewModel handles empty password for existing profiles (means "don't change").
        }

        viewModel.insertOrUpdateProfile(
            profileName = profileName,
            host = host,
            portStr = portStr,
            username = username,
            authMethodIsKey = isKeyAuthSelected,
            credential = credentialToSave,
            keyAlias = keyAliasToSave,
            isNewProfile = isNewProfile,
            profileId = args.profileId
        )
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
