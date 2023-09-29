package com.cosmos.unreddit.data.local.backup

import android.content.Context
import android.net.Uri
import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.local.mapper.BackupCommentMapper
import com.cosmos.unreddit.data.local.mapper.BackupPostMapper
import com.cosmos.unreddit.data.local.mapper.ProfileMapper
import com.cosmos.unreddit.data.local.mapper.SubscriptionMapper
import com.cosmos.unreddit.data.model.backup.Backup
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.di.DispatchersModule.IoDispatcher
import com.cosmos.unreddit.di.NetworkModule.BackupMoshi
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import okio.use
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StealthBackupManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    redditDatabase: RedditDatabase,
    profileMapper: ProfileMapper,
    subscriptionMapper: SubscriptionMapper,
    backupPostMapper: BackupPostMapper,
    backupCommentMapper: BackupCommentMapper,
    @BackupMoshi private val moshi: Moshi,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BackupManager(
    redditDatabase,
    profileMapper,
    subscriptionMapper,
    backupPostMapper,
    backupCommentMapper,
    defaultDispatcher
) {

    override suspend fun import(uri: Uri): Result<Backup> {
        val adapter = moshi.adapter(Backup::class.java)

        return runCatching {
            withContext(ioDispatcher) {
                appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.source().buffer().use { bufferedSource ->
                        adapter.fromJson(bufferedSource)
                    }
                } ?: Backup()
            }
        }.onSuccess { backup ->
            insertProfiles(backup.profiles)
        }
    }

    override suspend fun export(uri: Uri): Result<Backup> {
        val backup = Backup(profiles = getProfiles())

        val adapter = moshi.adapter(Backup::class.java).indent("  ")

        return runCatching {
            withContext(ioDispatcher) {
                appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.sink().buffer().use { bufferedSink ->
                        adapter.toJson(bufferedSink, backup)
                    }
                }
            }
            backup
        }
    }
}
