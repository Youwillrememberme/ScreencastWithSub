package com.subcast.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Resume-playback record keyed by the video content Uri. */
@Entity(tableName = "resume")
data class ResumeRecord(
    @PrimaryKey val videoUri: String,
    val videoName: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ResumeRecord>>

    @Query("SELECT * FROM resume WHERE videoUri = :uri")
    suspend fun get(uri: String): ResumeRecord?

    @Upsert
    suspend fun upsert(record: ResumeRecord)

    @Query("DELETE FROM resume WHERE videoUri = :uri")
    suspend fun delete(uri: String)
}

@Database(entities = [ResumeRecord::class], version = 1, exportSchema = false)
abstract class SubCastDatabase : RoomDatabase() {
    abstract fun resumeDao(): ResumeDao

    companion object {
        @Volatile
        private var INSTANCE: SubCastDatabase? = null

        fun get(context: Context): SubCastDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SubCastDatabase::class.java,
                    "subcast.db"
                ).build().also { INSTANCE = it }
            }
    }
}
