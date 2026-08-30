package com.vocaease.patient.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
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
    entities = [DraftEntity::class, MediaEntity::class, UploadJobEntity::class, PreparationDraftEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
internal abstract class VocaEaseDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun mediaDao(): MediaDao
    abstract fun uploadDao(): UploadDao
    abstract fun preparationDraftDao(): PreparationDraftDao

    companion object {
        fun create(
            context: Context,
            name: String = "vocaease-patient.db",
            allowMainThreadQueries: Boolean = false,
        ): VocaEaseDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, VocaEaseDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `preparation_drafts` (
                      `account_scope` TEXT NOT NULL,
                      `draft_id` TEXT NOT NULL,
                      `song_id` TEXT NOT NULL,
                      `song_title` TEXT NOT NULL,
                      `song_artist` TEXT NOT NULL,
                      `song_duration_seconds` INTEGER NOT NULL,
                      `server_session_id` TEXT,
                      `creation_key` TEXT NOT NULL,
                      `created_at` INTEGER NOT NULL,
                      `expires_at` INTEGER NOT NULL,
                      PRIMARY KEY(`account_scope`, `draft_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_preparation_drafts_account_scope_creation_key` " +
                        "ON `preparation_drafts` (`account_scope`, `creation_key`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_preparation_drafts_account_scope_server_session_id` " +
                        "ON `preparation_drafts` (`account_scope`, `server_session_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_preparation_drafts_account_scope_expires_at` " +
                        "ON `preparation_drafts` (`account_scope`, `expires_at`)",
                )
            }
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
        "preparation_drafts_guard_insert_v2", "preparation_drafts_guard_update_v2",
    )

    fun callback(context: Context) = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(context, db)
        override fun onOpen(db: SupportSQLiteDatabase) = install(context, db)
    }

    private fun install(context: Context, db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
        val canonicalStatements = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            .split(BOUNDARY)
            .map(String::trim)
            .filter(String::isNotEmpty)
        val canonicalByName = canonicalStatements.associateBy(::triggerName)
        check(canonicalByName.keys == triggerNames.toSet()) { "数据库约束定义不完整" }

        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            triggerNames.forEach { name -> db.execSQL("DROP TRIGGER IF EXISTS $name") }
            canonicalStatements.forEach(db::execSQL)
            check(installedTriggers(db) == canonicalByName.mapValues { (_, sql) -> normalizeSql(sql) }) {
                "数据库约束安装失败"
            }
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun installedTriggers(db: SupportSQLiteDatabase): Map<String, String> = db.query(
        "SELECT name, sql FROM sqlite_master WHERE type='trigger' ORDER BY name",
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name in triggerNames) put(name, normalizeSql(cursor.getString(1)))
            }
        }
    }

    private fun triggerName(statement: String): String = requireNotNull(
        Regex("CREATE TRIGGER(?: IF NOT EXISTS)? ([^ ]+)").find(statement),
    ).groupValues[1]

    private fun normalizeSql(sql: String): String = sql
        .trim()
        .removeSuffix(";")
        .replace(Regex("\\s+"), " ")
        .replace("CREATE TRIGGER IF NOT EXISTS ", "CREATE TRIGGER ")
}
