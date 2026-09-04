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

    @TypeConverter
    fun fromUploadPipelineStage(value: UploadPipelineStage): String = value.name

    @TypeConverter
    fun toUploadPipelineStage(value: String): UploadPipelineStage = enumValueOrReject(value)

    @TypeConverter
    fun fromUploadLocalActionType(value: UploadLocalActionType): String = value.name

    @TypeConverter
    fun toUploadLocalActionType(value: String): UploadLocalActionType = enumValueOrReject(value)

    @TypeConverter
    fun fromUploadLocalActionStage(value: UploadLocalActionStage): String = value.name

    @TypeConverter
    fun toUploadLocalActionStage(value: String): UploadLocalActionStage = enumValueOrReject(value)

    @TypeConverter
    fun fromPreparationDraftStatus(value: PreparationDraftStatus): String = value.name

    @TypeConverter
    fun toPreparationDraftStatus(value: String): PreparationDraftStatus = enumValueOrReject(value)

    @TypeConverter
    fun fromAccountExitChoice(value: AccountExitChoice): String = value.name

    @TypeConverter
    fun toAccountExitChoice(value: String): AccountExitChoice = enumValueOrReject(value)

    @TypeConverter
    fun fromAccountExitStage(value: AccountExitStage): String = value.name

    @TypeConverter
    fun toAccountExitStage(value: String): AccountExitStage = enumValueOrReject(value)

    private inline fun <reified T : Enum<T>> enumValueOrReject(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("数据库状态值无效")
}

@Database(
    entities = [
        DraftEntity::class, MediaEntity::class, UploadJobEntity::class, PreparationDraftEntity::class,
        UploadLocalActionEntity::class,
        AnalysisCheckpointEntity::class,
        AccountExitIntentEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
internal abstract class VocaEaseDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun mediaDao(): MediaDao
    abstract fun uploadDao(): UploadDao
    abstract fun preparationDraftDao(): PreparationDraftDao
    abstract fun uploadLocalActionDao(): UploadLocalActionDao
    abstract fun analysisCheckpointDao(): AnalysisCheckpointDao
    abstract fun accountExitIntentDao(): AccountExitIntentDao

    companion object {
        fun create(
            context: Context,
            name: String = "vocaease-patient.db",
            allowMainThreadQueries: Boolean = false,
        ): VocaEaseDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, VocaEaseDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `preparation_drafts` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ABANDONED'")
                db.execSQL("ALTER TABLE `preparation_drafts` ADD COLUMN `active_song_id` TEXT")
                db.execSQL(
                    """
                    UPDATE preparation_drafts
                    SET status = CASE WHEN server_session_id IS NULL THEN 'PENDING' ELSE 'BOUND' END,
                        active_song_id = song_id
                    WHERE NOT EXISTS (
                      SELECT 1 FROM preparation_drafts AS older
                      WHERE older.account_scope = preparation_drafts.account_scope
                        AND older.song_id = preparation_drafts.song_id
                        AND (
                          older.created_at < preparation_drafts.created_at OR
                          (older.created_at = preparation_drafts.created_at AND older.draft_id < preparation_drafts.draft_id)
                        )
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_preparation_drafts_account_scope_active_song_id` " +
                        "ON `preparation_drafts` (`account_scope`, `active_song_id`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TRIGGER IF EXISTS upload_jobs_guard_insert_v1")
                db.execSQL("DROP TRIGGER IF EXISTS upload_jobs_guard_update_v1")
                db.execSQL("ALTER TABLE `upload_jobs` ADD COLUMN `pipeline_stage` TEXT NOT NULL DEFAULT 'PAUSED'")
                db.execSQL("ALTER TABLE `upload_jobs` ADD COLUMN `progress_percent` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `upload_jobs` ADD COLUMN `receipt_wait_attempt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE upload_jobs SET audio_grant_key='grant:' || draft_id || ':audio', video_grant_key='grant:' || draft_id || ':video', submit_key='submit:' || draft_id")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `upload_jobs` ADD COLUMN `resume_pipeline_stage` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `upload_local_actions` (
                      `account_scope` TEXT NOT NULL,
                      `draft_id` TEXT NOT NULL,
                      `action` TEXT NOT NULL,
                      `stage` TEXT NOT NULL,
                      `audio_encrypted_relative_path` TEXT NOT NULL,
                      `video_encrypted_relative_path` TEXT NOT NULL,
                      PRIMARY KEY(`account_scope`, `draft_id`),
                      FOREIGN KEY(`account_scope`, `draft_id`) REFERENCES `drafts`(`account_scope`, `draft_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_upload_local_actions_account_scope_draft_id` " +
                        "ON `upload_local_actions` (`account_scope`, `draft_id`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `upload_jobs` ADD COLUMN `operation_version` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `analysis_checkpoints` (
                      `account_scope` TEXT NOT NULL,
                      `session_id` TEXT NOT NULL,
                      `account_scope_hash` TEXT NOT NULL,
                      `incarnation_proof` TEXT NOT NULL,
                      `status` TEXT NOT NULL,
                      `analysis_generation` INTEGER NOT NULL,
                      `poll_step` INTEGER NOT NULL,
                      `next_deadline_at` INTEGER NOT NULL,
                      `operation_version` INTEGER NOT NULL,
                      PRIMARY KEY(`account_scope`, `session_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_analysis_checkpoints_account_scope_next_deadline_at` " +
                        "ON `analysis_checkpoints` (`account_scope`, `next_deadline_at`)",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `analysis_checkpoints_v8` (
                      `account_scope_hash` TEXT NOT NULL,
                      `session_id` TEXT NOT NULL,
                      `incarnation_proof` TEXT NOT NULL,
                      `status` TEXT NOT NULL,
                      `analysis_generation` INTEGER NOT NULL,
                      `poll_step` INTEGER NOT NULL,
                      `next_deadline_at` INTEGER NOT NULL,
                      `operation_version` INTEGER NOT NULL,
                      PRIMARY KEY(`account_scope_hash`, `session_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO `analysis_checkpoints_v8`(" +
                        "account_scope_hash,session_id,incarnation_proof,status,analysis_generation,poll_step,next_deadline_at,operation_version" +
                        ") SELECT account_scope_hash,session_id,incarnation_proof,status,analysis_generation,poll_step,next_deadline_at,operation_version " +
                        "FROM `analysis_checkpoints`",
                )
                db.execSQL("DROP TABLE `analysis_checkpoints`")
                db.execSQL("ALTER TABLE `analysis_checkpoints_v8` RENAME TO `analysis_checkpoints`")
                db.execSQL(
                    "CREATE INDEX `index_analysis_checkpoints_account_scope_hash_next_deadline_at` " +
                        "ON `analysis_checkpoints` (`account_scope_hash`, `next_deadline_at`)",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `account_exit_intents` (
                      `account_scope` TEXT NOT NULL,
                      `account_scope_hash` TEXT NOT NULL,
                      `incarnation_proof` TEXT NOT NULL,
                      `session_epoch` INTEGER NOT NULL,
                      `operation_id` TEXT NOT NULL,
                      `choice` TEXT NOT NULL,
                      `stage` TEXT NOT NULL,
                      `operation_version` INTEGER NOT NULL,
                      PRIMARY KEY(`account_scope`)
                    )
                    """.trimIndent(),
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
        "upload_local_actions_guard_insert_v1", "upload_local_actions_guard_update_v1",
        "preparation_drafts_guard_insert_v2", "preparation_drafts_guard_update_v2",
        "analysis_checkpoints_guard_insert_v1", "analysis_checkpoints_guard_update_v1",
        "account_exit_intents_guard_insert_v1", "account_exit_intents_guard_update_v1",
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
