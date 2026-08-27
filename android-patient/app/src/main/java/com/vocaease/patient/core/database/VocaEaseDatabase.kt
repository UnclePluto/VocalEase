package com.vocaease.patient.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

class DatabaseConverters {
    @TypeConverter
    fun fromDraftState(value: DraftState): String = value.name

    @TypeConverter
    fun toDraftState(value: String): DraftState = enumValueOrReject(value)

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = enumValueOrReject(value)

    @TypeConverter
    fun fromMediaValidationState(value: MediaValidationState): String = value.name

    @TypeConverter
    fun toMediaValidationState(value: String): MediaValidationState = enumValueOrReject(value)

    @TypeConverter
    fun fromUploadStepState(value: UploadStepState): String = value.name

    @TypeConverter
    fun toUploadStepState(value: String): UploadStepState = enumValueOrReject(value)

    @TypeConverter
    fun fromUploadOverallState(value: UploadOverallState): String = value.name

    @TypeConverter
    fun toUploadOverallState(value: String): UploadOverallState = enumValueOrReject(value)

    private inline fun <reified T : Enum<T>> enumValueOrReject(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("数据库状态值无效")
}

@Database(
    entities = [DraftEntity::class, MediaEntity::class, UploadJobEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
internal abstract class VocaEaseDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun mediaDao(): MediaDao
    abstract fun uploadDao(): UploadDao

    companion object {
        fun create(
            context: Context,
            name: String = "vocaease-patient.db",
            allowMainThreadQueries: Boolean = false,
        ): VocaEaseDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, VocaEaseDatabase::class.java, name)
                .addCallback(DatabaseConstraintInstaller.callback(context.applicationContext))
            if (allowMainThreadQueries) builder.allowMainThreadQueries()
            return builder.build()
        }

        fun inMemory(context: Context, allowMainThreadQueries: Boolean = false): VocaEaseDatabase {
            val builder = Room.inMemoryDatabaseBuilder(context.applicationContext, VocaEaseDatabase::class.java)
                .addCallback(DatabaseConstraintInstaller.callback(context.applicationContext))
            if (allowMainThreadQueries) builder.allowMainThreadQueries()
            return builder.build()
        }
    }
}

internal object DatabaseConstraintInstaller {
    private const val ASSET = "database/v1_constraints.sql"
    private const val BOUNDARY = "-- VOCAEASE-STATEMENT"

    val triggerNames = listOf(
        "drafts_guard_insert_v1", "drafts_guard_update_v1",
        "media_guard_insert_v1", "media_guard_update_v1",
        "upload_jobs_guard_insert_v1", "upload_jobs_guard_update_v1",
    )

    fun callback(context: Context) = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(context, db)
        override fun onOpen(db: SupportSQLiteDatabase) = install(context, db)
    }

    private fun install(context: Context, db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
            .split(BOUNDARY)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(db::execSQL)
    }
}
