package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.db.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLDecoder
import java.util.jar.JarFile
import kotlin.time.Clock
import kotlin.time.measureTime

object DatabaseManager {

    private const val MIGRATION_FOLDER_PATH = "db/migrations"
    private const val CREATE_TABLE_DB_MIGRATIONS_FILE_NAME =
        "V0__CREATE_TABLE_MIGRATION.sql"

    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)

    private var db: R2dbcDatabase? = null

    // we only allow 16 concurrent database operations
    // this is to prevent too many database operations from being executed at the same time
    // which can lead to the database MAX_CONNECTIONS (already been there, never doing that again)
    private val semaphore = Semaphore(16)

    suspend fun init() {
        connect()
        initTables()
    }

    private fun connect() {
        if (db != null) return

        try {
            Class.forName("org.postgresql.Driver")
        } catch (e: ClassNotFoundException) {
            logger.error("PostgreSQL driver not found, check dependencies", e)
            throw e
        }

        db = R2dbcDatabase.connect(
            url = Config.POSTGRES_URL,
            driver = "postgresql",
            user = Config.POSTGRES_USER,
            password = Config.POSTGRES_PASSWORD,
        )
    }

    suspend fun <T> transaction(
        block: suspend R2dbcTransaction.() -> T
    ): T {
        val database = db ?: error("Database not connected")
        semaphore.withPermit {
            return suspendTransaction(db = database, statement = block)
        }
    }

    private suspend fun initTables() {
        logger.info("Initializing database migrations")

        transaction {
            SchemaUtils.create(MigrationTable)
        }

        val migrationFiles = loadAllMigrationFileNames()

        if (migrationFiles.isEmpty()) {
            logger.warn("No migration files found")
            return
        }

        val appliedMigrations = transaction {
            MigrationTable
                .select(MigrationTable.migration)
                .map { it[MigrationTable.migration] }
                .toList()
        }

        val firstRun = appliedMigrations.isEmpty()
        if (firstRun) {
            logger.info("First run, just initializing database, ignoring migrations")
            transaction {
                SchemaUtils.create(
                    ApproveTeamMemberTable,
                    CachedVerificationStatusTable,
                    InstallRequestPreviousResolutionTable,
                    InstallRequestScopeTable,
                    InstallRequestTable,
                    AppRestrictionTable,
                    InstallRequestHistoryTable,
                    RestrictedScopesTable,
                )
                MigrationTable.batchInsert(migrationFiles) {
                    this[MigrationTable.migration] = it
                    this[MigrationTable.appliedAt] = Clock.System.now()
                }
            }
            return
        }

        if (CREATE_TABLE_DB_MIGRATIONS_FILE_NAME !in appliedMigrations) {
            transaction {
                MigrationTable.insert {
                    it[migration] = CREATE_TABLE_DB_MIGRATIONS_FILE_NAME
                    it[appliedAt] = Clock.System.now()
                }
            }
        }

        val migrationsToApply = migrationFiles
            .filter {
                it != CREATE_TABLE_DB_MIGRATIONS_FILE_NAME &&
                        it !in appliedMigrations
            }
            .sorted()

        if (migrationsToApply.isEmpty()) {
            logger.info("Database is up to date")
            return
        }

        logger.info("Found ${migrationsToApply.size} migrations to apply")

        val migrations = loadMigrations(migrationsToApply)

        val missing = migrationsToApply - migrations.keys

        if (missing.isNotEmpty()) {
            error(
                "Missing migration files: ${
                    missing.joinToString(", ")
                }"
            )
        }

        for ((fileName, sql) in migrations.entries.sortedBy { it.key }) {
            logger.info("Applying migration {}", fileName)

            try {
                val duration = measureTime {
                    transaction {
                        exec(sql)
                    }
                }
                transaction {
                    MigrationTable.insert {
                        it[migration] = fileName
                        it[appliedAt] = Clock.System.now()
                    }
                }
                logger.info(
                    "Migration {} applied in {} ms",
                    fileName,
                    duration.inWholeMilliseconds
                )
            } catch (e: Exception) {
                logger.error(
                    "Failed to apply migration {}",
                    fileName,
                    e
                )
                throw e
            }
        }

        logger.info("All migrations applied")
    }

    private fun loadMigrations(
        files: List<String>
    ): Map<String, String> {
        val classLoader = javaClass.classLoader

        return buildMap {
            for (file in files) {
                try {
                    val stream = classLoader.getResourceAsStream("$MIGRATION_FOLDER_PATH/$file")
                    if (stream == null) {
                        logger.error(
                            "Migration {} not found",
                            file
                        )
                        continue
                    }
                    stream.bufferedReader().use {
                        put(file, it.readText())
                    }
                } catch (e: Exception) {
                    logger.error(
                        "Failed to load migration {}",
                        file,
                        e
                    )
                }
            }
        }
    }

    private fun loadAllMigrationFileNames(): List<String> {
        val result = mutableListOf<String>()
        val resources = javaClass.classLoader.getResources(MIGRATION_FOLDER_PATH)

        while (resources.hasMoreElements()) {
            val url = resources.nextElement()
            when (url.protocol) {
                "jar" -> {
                    val path = url.path
                        .removePrefix("file:")
                        .substringBefore("!")

                    JarFile(
                        URLDecoder.decode(path, "UTF-8")
                    ).use { jar ->
                        jar.entries()
                            .asSequence()
                            .filter {
                                it.name.startsWith(MIGRATION_FOLDER_PATH) &&
                                        !it.isDirectory &&
                                        it.name.endsWith(".sql")
                            }
                            .map {
                                it.name.removePrefix(
                                    "$MIGRATION_FOLDER_PATH/"
                                )
                            }
                            .forEach(result::add)
                    }
                }

                "file" -> {
                    val file = File(
                        url.path.removePrefix("file:")
                    )
                    if (!file.isDirectory) continue

                    file.listFiles()
                        ?.filter {
                            it.isFile &&
                                    it.name.endsWith(".sql")
                        }
                        ?.forEach {
                            result += it.name
                        }
                }

                else -> {
                    logger.warn(
                        "Unsupported protocol {}",
                        url.protocol
                    )
                }
            }
        }

        return result.distinct().sorted()
    }
}