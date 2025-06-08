package com.example.sshinjector.ui.profiles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sshinjector.R
import com.example.sshinjector.databinding.FragmentProfileListBinding
import com.example.sshinjector.model.SSHProfile

class ProfileListFragment : Fragment() {

    private var _binding: FragmentProfileListBinding? = null
    private val binding get() = _binding!!

    // Use navGraphViewModels to scope ViewModel to the navigation graph
    private val viewModel: ProfileViewModel by navGraphViewModels(R.id.nav_graph)
    private lateinit var profileAdapter: SSHProfileAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        profileAdapter = SSHProfileAdapter(
            onProfileClick = { profile ->
                // TODO: Navigate to main connection screen with this profile (Step 6)
                Toast.makeText(context, "Selected profile: ${profile.profileName} (ID: ${profile.id})", Toast.LENGTH_SHORT).show()
                // Example for future:
                // val action = ProfileListFragmentDirections.actionProfileListFragmentToConnectionFragment(profile.id)
                // findNavController().navigate(action)
            },
            onEditClick = { profile ->
                // Navigate to AddEditProfileDialog with the profile's ID using Safe Args
                val action = ProfileListFragmentDirections.actionProfileListFragmentToAddEditProfileDialog(profile.id)
                findNavController().navigate(action)
            },
            onDeleteClick = { profile ->
                showDeleteConfirmationDialog(profile)
            }
        )
        binding.profilesRecyclerView.apply {
            adapter = profileAdapter
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, LinearLayoutManager.VERTICAL))
        }
    }

    private fun setupFab() {
        binding.fabAddProfile.setOnClickListener {
            // Navigate to AddEditProfileDialog for a new profile (ID -1L) using Safe Args
            val action = ProfileListFragmentDirections.actionProfileListFragmentToAddEditProfileDialog(-1L)
            findNavController().navigate(action)
        }
    }

    private fun observeViewModel() {
        viewModel.allProfiles.observe(viewLifecycleOwner) { profiles ->
            profiles?.let {
                profileAdapter.submitList(it)
                binding.emptyListTextview.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewModel.eventMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                // Only show messages not handled by AddEditProfileDialog's saveEvent
                // This typically would be for delete confirmations or other general messages.
                if (!it.startsWith("Profile saved:") && !it.startsWith("Profile updated:") && !it.contains("Error saving profile")) {
                     Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                }
                viewModel.onEventMessageShown() // Consume the message
            }
        }
    }

    private fun showDeleteConfirmationDialog(profile: SSHProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_profile_title))
            .setMessage(getString(R.string.delete_profile_confirmation_message, profile.profileName))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteProfile(profile)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.profilesRecyclerView.adapter = null // Clear adapter to help with memory leaks
        _binding = null
    }
}
