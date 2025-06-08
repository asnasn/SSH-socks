package com.example.sshinjector.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.sshinjector.model.SSHProfile

@Dao
interface SSHProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: SSHProfile): Long // Room returns Long for auto-generated PK

    @Update
    suspend fun update(profile: SSHProfile)

    @Delete
    suspend fun delete(profile: SSHProfile)

    @Query("SELECT * FROM ssh_profiles WHERE id = :id")
    fun getProfileById(id: Long): LiveData<SSHProfile?> // Changed to Long

    @Query("SELECT * FROM ssh_profiles ORDER BY profileName ASC")
    fun getAllProfiles(): LiveData<List<SSHProfile>>

    @Query("SELECT * FROM ssh_profiles WHERE id = :id")
    suspend fun getProfileByIdSync(id: Long): SSHProfile? // Changed to Long
}
