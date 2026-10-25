package com.grokrun.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val mode: String,
    val mascot: String,
    val configJson: String,
    val distanceM: Double,
    val durationSec: Double,
    val elevGain: Double,
    val elevLoss: Double,
    val elevEstimated: Boolean,
    val elevSource: String,
    val inZoneRatio: Double?,
    val phasesJson: String,
    val splitsJson: String,
)

@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutId")],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val workoutId: String,
    val lat: Double,
    val lon: Double,
    val t: Long,
    val acc: Float,
    val alt: Double?,
    val altSource: String,
    val phase: String,
    val phaseIndex: Int,
    val distM: Double,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String,
    val configJson: String,
)

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY startedAt DESC")
    suspend fun all(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun one(id: String): WorkoutEntity?

    @Query("SELECT * FROM points WHERE workoutId = :id ORDER BY t")
    suspend fun points(id: String): List<PointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workout: WorkoutEntity)

    @Insert
    suspend fun insertPoints(points: List<PointEntity>)

    @Query("DELETE FROM points WHERE workoutId = :id")
    suspend fun deletePoints(id: String)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM presets ORDER BY name")
    suspend fun presets(): List<PresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deletePreset(id: String)
}

@Database(
    entities = [WorkoutEntity::class, PointEntity::class, PresetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "grokrun.db").build()
    }
}
