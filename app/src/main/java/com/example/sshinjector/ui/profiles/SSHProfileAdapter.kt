package com.example.sshinjector.ui.profiles

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sshinjector.R
import com.example.sshinjector.model.SSHProfile

class SSHProfileAdapter(
    private val onProfileClick: (SSHProfile) -> Unit,
    private val onEditClick: (SSHProfile) -> Unit,
    private val onDeleteClick: (SSHProfile) -> Unit
) : ListAdapter<SSHProfile, SSHProfileAdapter.ProfileViewHolder>(ProfileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ssh_profile, parent, false)
        return ProfileViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = getItem(position)
        holder.bind(profile, onProfileClick, onEditClick, onDeleteClick)
    }

    class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.profile_name_textview)
        private val detailsTextView: TextView = itemView.findViewById(R.id.profile_details_textview)
        // private val iconImageView: ImageView = itemView.findViewById(R.id.profile_icon) // Icon can be set if needed
        private val editButton: ImageButton = itemView.findViewById(R.id.button_edit_profile)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.button_delete_profile)

        fun bind(
            profile: SSHProfile,
            onProfileClick: (SSHProfile) -> Unit,
            onEditClick: (SSHProfile) -> Unit,
            onDeleteClick: (SSHProfile) -> Unit
        ) {
            nameTextView.text = profile.profileName
            detailsTextView.text = "${profile.username}@${profile.host}:${profile.port}"

            itemView.setOnClickListener { onProfileClick(profile) }
            editButton.setOnClickListener { onEditClick(profile) }
            deleteButton.setOnClickListener { onDeleteClick(profile) }
        }
    }

    class ProfileDiffCallback : DiffUtil.ItemCallback<SSHProfile>() {
        override fun areItemsTheSame(oldItem: SSHProfile, newItem: SSHProfile): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SSHProfile, newItem: SSHProfile): Boolean {
            return oldItem == newItem // Relies on the data class equals()
        }
    }
}
