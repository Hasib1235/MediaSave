package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.example.domain.model.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' AND mode = 'VIDEO' ORDER BY completedAt DESC")
    fun observeCompletedVideoTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' AND mode = 'MUSIC' ORDER BY completedAt DESC")
    fun observeCompletedMusicTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DownloadTaskEntity?

    @Query(
        "SELECT * FROM download_tasks WHERE sourceUrl = :sourceUrl AND mode = :mode " +
            "AND COALESCE(resolutionLabel, '') = :resolutionLabel " +
            "AND COALESCE(targetAudioFormat, '') = :targetAudioFormat " +
            "AND status != 'CANCELLED' AND status != 'FAILED' LIMIT 1"
    )
    suspend fun findDuplicateTask(
        sourceUrl: String,
        mode: String,
        resolutionLabel: String,
        targetAudioFormat: String
    ): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: DownloadTaskEntity)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query(
        "UPDATE download_tasks SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, " +
            "speedBytesPerSec = :speedBytesPerSec, etaSeconds = :etaSeconds, " +
            "status = :status, statusDetail = :statusDetail WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        etaSeconds: Long,
        status: String,
        statusDetail: String
    )

    @Query("UPDATE download_tasks SET fileName = :newFileName, title = :newTitle, outputFilePath = :newPath WHERE id = :id")
    suspend fun updateFileMetadata(id: String, newTitle: String, newFileName: String, newPath: String)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_tasks WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinishedHistory()

    @Query(
        "UPDATE download_tasks SET status = 'PAUSED', " +
            "statusDetail = 'Interrupted by app restart • Tap Resume to continue', " +
            "speedBytesPerSec = 0, etaSeconds = -1 " +
            "WHERE status IN ('QUEUED', 'DOWNLOADING', 'MERGING_STREAMS', 'EXTRACTING_AUDIO', 'CONVERTING_AUDIO', 'VALIDATING')"
    )
    suspend fun recoverInterruptedTasksOnStartup(): Int

    @Query(
        "SELECT * FROM download_tasks WHERE status = 'FAILED' AND " +
            "(errorMessage LIKE '%403%' OR errorMessage LIKE '%401%' OR errorMessage LIKE '%Access denied%')"
    )
    suspend fun getFailedHttp403Tasks(): List<DownloadTaskEntity>
}

@Database(entities = [DownloadTaskEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mediasave_database.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
