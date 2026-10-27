package com.fantamomo.slack.approver.data

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

@Suppress("SameParameterValue")
object Config {
    private val logger = LoggerFactory.getLogger(Config::class.java)

    private val configPath: Path =
        Path(System.getenv("CONFIG_FILE_PATH") ?: "config.properties")

    private val properties = Properties()

    private val entries = mutableListOf<ConfigEntry<*>>()

    // only true if the file does not exist and any environment variable is provided
    private var skipConfigCreation = false

    val PORT by int(
        key = "server.port",
        default = 80,
        description = "The port the HTTP server should listen on.",
    )

    val HOST by string(
        key = "server.host",
        default = "0.0.0.0",
        description = "The host/interface the server should bind to.",
    )

    val POSTGRES_URL: String by string(
        key = "postgres.url",
        default = "r2dbc:postgresql://localhost:5432/postgres",
        description = "The JDBC URL of the Postgres database (prefixed with 'r2dbc:postgresql:').",
    )

    val POSTGRES_USER: String by string(
        key = "postgres.user",
        default = "postgres",
        description = "The username for the Postgres database.",
    )

    val POSTGRES_PASSWORD: String by string(
        key = "postgres.password",
        default = "postgres",
        description = "The password for the Postgres database.",
    )

    val SLACK_BOT_TOKEN: String by string(
        key = "slack.bot.token",
        description = "The Slack bot token.",
    )

    val SLACK_USER_TOKEN: String by string(
        key = "slack.user.token",
        description = "The Slack user token.",
    )

    val SLACK_APP_TOKEN: String by string(
        key = "slack.app.token",
        description = "The Slack app token. (Used in Socket Mode)",
        default = "",
    )

    val SLACK_SIGNING_SECRET: String by string(
        key = "slack.signing.secret",
        description = "The Slack signing secret (Used to verify HTTP requests from slack)",
        default = "",
    )

    val SLACK_CLIENT_TOKEN: String by string(
        key = "slack.client.token",
        description = "The Slack client token. (Required for command checking)",
        default = "",
    )

    val SLACK_SESSION_COOKIE: String by string(
        key = "slack.session.cookie",
        description = "The Slack session cookie starting with 'xoxd-' (Cookie name is 'd') (Required for command checking)",
        default = "",
    )

    val SLACK_CHANNEL_REVIEW: String by string(
        key = "slack.channel.review",
        description = "The Slack channel for reviews.",
    )

    val SLACK_CHANNEL_LOG: String by string(
        key = "slack.channel.log",
        description = "The Slack channel for review logs.",
    )

    val SOCKET_MODE: Boolean by boolean(
        key = "socket.mode",
        description = "Whether to use socket mode.",
        default = false,
    )

    val ADMIN_USERS: Set<String> by string(
        key = "admin.users",
        description = "Comma separated list of users with permission to the admin commands.",
        default = "",
    ).map { it.split(",").map { it.trim() }.filter { it.startsWith('W') || it.startsWith('U') }.toSet() }

    val SLACK_SLASH_COMMAND: String by string(
        key = "slack.slash.command",
        description = "The Slack slash command. (Used to react to the slash command and for messages)",
        default = "/app-approver",
    )

    fun init() {
        if (properties.isNotEmpty()) return
        mayCreateConfig()
        loadProperties()
        validateAndLoad()
    }

    private fun string(
        key: String,
        default: String? = null,
        description: String,
    ) = register(key, default, description) { it }

    private fun int(
        key: String,
        default: Int? = null,
        description: String,
    ) = register(key, default, description) { value ->
        value.toIntOrNull()
            ?: throw IllegalArgumentException("Expected a valid integer.")
    }

    private fun boolean(
        key: String,
        default: Boolean? = null,
        description: String,
    ) = register(key, default, description) { value ->
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Expected 'true' or 'false'.")
        }
    }

    private fun path(
        key: String,
        default: Path? = null,
        description: String,
    ) = register(key, default, description) { value ->
        Path(value).toAbsolutePath()
    }

    private fun <T> register(
        key: String,
        default: T?,
        description: String,
        parser: (String) -> T
    ): ReadOnlyProperty<Config, T> {

        val entry = ConfigEntry(
            key = key,
            default = default,
            description = description,
            parser = parser
        )

        entries += entry

        return entry
    }

    private fun validateAndLoad() {

        val errors = mutableListOf<String>()

        for (entry in entries) {

            @Suppress("UNCHECKED_CAST")
            entry as ConfigEntry<Any>


            val envValue = System.getenv(entry.envKey)
            if (envValue != null) {
                try {
                    entry.setValue(entry.parser(envValue.trim()))
                    logger.info(
                        "Config '{}' is set via environment variable '{}'.",
                        entry.key,
                        entry.envKey
                    )
                    continue
                } catch (ex: Exception) {
                    logger.warn(
                        "Failed to parse environment variable '{}'. Value: '{}'. Reason: {}",
                        entry.envKey,
                        envValue,
                        ex.message
                    )
                }
            }

            val rawValue = properties.getProperty(entry.key)?.replace("\r", "\\")

            if (rawValue == null) {
                if (entry.default != null) {
                    logger.warn(
                        "Missing config '{}'. Using default '{}'. Description: {}",
                        entry.key,
                        entry.default,
                        entry.description
                    )

                    entry.setValue(entry.default)
                    continue
                }

                errors += buildString {
                    appendLine("Missing required configuration.")
                    appendLine("Key: ${entry.key}")
                    appendLine("Description: ${entry.description}")
                }

                continue
            }

            try {
                val parsed = entry.parser(rawValue.trim())
                entry.setValue(parsed)
            } catch (ex: Exception) {
                errors += buildString {
                    appendLine("Invalid configuration value.")
                    appendLine("Key: ${entry.key}")
                    appendLine("Value: $rawValue")
                    appendLine("Description: ${entry.description}")
                    appendLine("Reason: ${ex.message}")
                }
            }
        }

        if (errors.isNotEmpty()) {
            val message = buildString {
                appendLine("Failed to load configuration.")
                appendLine("Config file: ${configPath.absolutePathString()}")
                appendLine()
                errors.forEach {
                    appendLine(it)
                }
            }
            throw IllegalStateException(message)
        }

        logger.info("Successfully loaded {} config values.", entries.size)
    }

    private fun mayCreateConfig() {
        if (configPath.exists()) {
            return
        }

        for (entry in entries) {
            if (System.getenv(entry.envKey) != null) {
                logger.info("A environment ($entry.envKey) variable is provided, skipping config creation.")
                skipConfigCreation = true
                return
            }
        }

        logger.warn(
            "Config file does not exist. Creating default config at '{}'.",
            configPath.absolutePathString()
        )

        val defaultConfig = buildString {
            appendLine("# Bot Approver Configuration")
            appendLine()

            for (entry in entries) {
                appendLine("# ${entry.description}")

                if (entry.default != null) {
                    appendLine("${entry.key}=${entry.default}")
                } else {
                    appendLine("# ${entry.key}=")
                }

                appendLine()
            }
        }

        try {
            configPath.parent?.createDirectories()
            configPath.outputStream().bufferedWriter().use {
                it.write(defaultConfig)
            }

            logger.info(
                "Created default config at '{}'.",
                configPath.absolutePathString()
            )
        } catch (ex: IOException) {
            logger.warn(
                "Could not create default config file at '{}': {}",
                configPath.absolutePathString(),
                ex.message
            )
        }
    }

    private fun loadProperties() {
        if (!configPath.exists()) {
            if (skipConfigCreation) logger.info(
                "Config file '{}' does not exist, relying on environment variables and defaults.",
                configPath.absolutePathString()
            )
            return
        }
        try {
            configPath.inputStream().use(properties::load)

            logger.info(
                "Loaded config from '{}'.",
                configPath.absolutePathString()
            )
        } catch (ex: IOException) {
            throw IllegalStateException(
                "Failed to load config file '${configPath.absolutePathString()}'.",
                ex
            )
        }
    }

    private fun <I, O> ReadOnlyProperty<Config, I>.map(mapper: (I) -> O): ReadOnlyProperty<Config, O> = Mapper(this, mapper)

    private class Mapper<I, O>(
        private val input: ReadOnlyProperty<Config, I>,
        private val mapper: (I) -> O
    ) : ReadOnlyProperty<Config, O> {
        var mapped: Boolean = false
        var value: O? = null

        override fun getValue(
            thisRef: Config,
            property: KProperty<*>
        ): O {
            if (mapped) {
                return value!!
            }
            value = mapper(input.getValue(thisRef, property))
            mapped = true
            return value!!
        }
    }

        private class ConfigEntry<T>(
        val key: String,
        val default: T?,
        val description: String,
        val parser: (String) -> T,
    ): ReadOnlyProperty<Config, T> {
        private var internalValue: T? = null

        val envKey = key.uppercase().replace('.', '_')

        val value: T?
            get() = internalValue

        fun setValue(value: T) {
            internalValue = value
        }

        override fun getValue(
            thisRef: Config,
            property: KProperty<*>
        ): T {
            return internalValue ?: throw IllegalStateException(
                "Config entry '${property.name}' has not been initialized."
            )
        }
    }
}