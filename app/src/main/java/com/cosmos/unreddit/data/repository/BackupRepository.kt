package com.cosmos.unreddit.data.repository

import android.net.Uri
import com.cosmos.unreddit.data.local.backup.BackupManager
import com.cosmos.unreddit.data.local.backup.RedditBackupManager
import com.cosmos.unreddit.data.local.backup.StealthBackupManager
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.model.backup.Backup
import com.cosmos.unreddit.data.model.backup.BackupType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    private val stealthBackupManager: StealthBackupManager,
    private val redditBackupManager: RedditBackupManager
) {

    fun importProfiles(uri: Uri, type: BackupType): Flow<Resource<Backup>> = flow {
        emit(Resource.Loading())

        val backupManager = getBackupManager(type)
        backupManager
            .import(uri)
            .onSuccess { backup ->
                emit(Resource.Success(backup))
            }
            .onFailure { e ->
                emit(Resource.Error(throwable = e))
            }
    }

    fun exportProfiles(uri: Uri, type: BackupType): Flow<Resource<Backup>> = flow {
        emit(Resource.Loading())

        val backupManager = getBackupManager(type)
        backupManager
            .export(uri)
            .onSuccess { backup ->
                emit(Resource.Success(backup))
            }
            .onFailure { e ->
                emit(Resource.Error(throwable = e))
            }
    }

    private fun getBackupManager(type: BackupType): BackupManager {
        return when (type) {
            BackupType.STEALTH -> stealthBackupManager
            BackupType.REDDIT -> redditBackupManager
        }
    }
}
