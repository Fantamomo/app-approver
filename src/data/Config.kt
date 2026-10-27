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

    private val configPath: Path = Path(System.getenv("CONFIG_FILE_PATH") ?: "config.properties")

    private val properties = Properties()
    private val entries = mutableListOf<ConfigEntry<*>>()
    private var skipConfigCreation = false
    private var initialized = false

    val PORT by int(
        key = "server.port",
        default = 80,
        description = "The port the HTTP server should listen on."
    )

    val HOST by string(
        key = "server.host",
        default = "0.0.0.0",
        description = "The host/interface the server should bind to."
    )

    val POSTGRES_URL: String by string(
        key = "postgres.url",
        default = "r2dbc:postgresql://localhost:5432/postgres",
        description = "The R2DBC URL of the PostgreSQL database."
    )

    val POSTGRES_USER: String by string(
        key = "postgres.user",
        default = "postgres",
        description = "The username for the PostgreSQL database."
    )

    val POSTGRES_PASSWORD: String by string(
        key = "postgres.password",
        default = "postgres",
        description = "The password for the PostgreSQL database."
    )

    val SLACK_BOT_TOKEN: String by string(
        key = "slack.bot.token",
        description = "The Slack bot token."
    )

    val SLACK_USER_TOKEN: String by string(
        key = "slack.user.token",
        description = "The Slack user token."
    )

    val SLACK_APP_TOKEN: String by string(
        key = "slack.app.token",
        default = "",
        description = "The Slack app token. Used in Socket Mode."
    )

    val SLACK_SIGNING_SECRET: String by string(
        key = "slack.signing.secret",
        default = "",
        description = "The Slack signing secret used to verify HTTP requests from Slack."
    )

    val SLACK_CLIENT_TOKEN: String by string(
        key = "slack.client.token",
        default = "",
        description = "The Slack client token required for command checking."
    )

    val SLACK_SESSION_COOKIE: String by string(
        key = "slack.session.cookie",
        default = "",
        description = "The Slack session cookie starting with 'xoxd-' (cookie name 'd'), required for command checking."
    )

    val SLACK_CHANNEL_REVIEW: String by string(
        key = "slack.channel.review",
        description = "The Slack channel for reviews."
    )

    val SLACK_CHANNEL_LOG: String by string(
        key = "slack.channel.log",
        description = "The Slack channel for review logs."
    )

    val SOCKET_MODE: Boolean by boolean(
        key = "socket.mode",
        default = false,
        description = "Whether to use Socket Mode."
    )

    val ADMIN_USERS: Set<String> by string(
        key = "admin.users",
        default = "",
        description = "Comma-separated list of users permitted to use admin commands."
    ).map("admin.users") { value ->
        value.split(",")
            .map(String::trim)
            .filter { it.startsWith('W') || it.startsWith('U') }
            .toSet()
    }

    val SLACK_SLASH_COMMAND: String by string(
        key = "slack.slash.command",
        default = "/app-approver",
        description = "The Slack slash command used for interactions and messages."
    )

    fun init() {
        if (initialized) return

        mayCreateConfig()
        loadProperties()
        validateAndLoad()
        initialized = true
    }

    private fun string(
        key: String,
        default: String? = null,
        description: String
    ) = register(key, default, description) { it }

    private fun int(
        key: String,
        default: Int? = null,
        description: String
    ) = register(key, default, description) { value ->
        value.toIntOrNull()
            ?: throw IllegalArgumentException(
                "Expected an integer but received '$value'."
            )
    }

    private fun boolean(
        key: String,
        default: Boolean? = null,
        description: String
    ) = register(key, default, description) { value ->
        when (value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException(
                "Expected 'true', '1', 'false', or '0' but received '$value'."
            )
        }
    }

    private fun path(
        key: String,
        default: Path? = null,
        description: String
    ) = register(key, default, description) { value ->
        Path(value).toAbsolutePath()
    }

    private fun <T> register(
        key: String,
        default: T?,
        description: String,
        parser: (String) -> T
    ): ConfigEntry<T> {
        check(!initialized) {
            "Cannot register configuration key '$key' after Config.init() has completed."
        }

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
            validateEntry(entry, errors)
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                buildString {
                    appendLine("Configuration validation failed.")
                    appendLine("Config file: ${configPath.toAbsolutePath()}")
                    appendLine()
                    errors.forEach {
                        appendLine(it)
                        appendLine()
                    }
                }.trimEnd()
            )
        }

        try {
            entries.forEach { it.validateMappings() }
        } catch (ex: Exception) {
            throw IllegalStateException(
                "Configuration mapping failed: ${ex.message}",
                ex
            )
        }

        logger.info("Successfully loaded and validated {} config values.", entries.size)
    }

    private fun validateEntry(
        entry: ConfigEntry<*>,
        errors: MutableList<String>
    ) {
        val envValue = System.getenv(entry.envKey)

        if (envValue != null) {
            try {
                entry.loadFromEnvironment(envValue.trim())
                logger.info(
                    "Configuration '{}' loaded from environment variable '{}'.",
                    entry.key,
                    entry.envKey
                )
                return
            } catch (ex: Exception) {
                errors += buildString {
                    appendLine("Invalid environment variable.")
                    appendLine("Key: ${entry.key}")
                    appendLine("Environment variable: ${entry.envKey}")
                    appendLine("Reason: ${ex.message ?: ex.javaClass.simpleName}")
                }
                return
            }
        }

        val rawValue = properties.getProperty(entry.key)

        if (rawValue == null) {
            if (entry.default != null) {
                try {
                    entry.loadDefault()
                    logger.warn(
                        "Configuration '{}' is missing. Using default '{}'. Description: {}",
                        entry.key,
                        entry.default,
                        entry.description
                    )
                } catch (ex: Exception) {
                    errors += buildString {
                        appendLine("Invalid default configuration value.")
                        appendLine("Key: ${entry.key}")
                        appendLine("Default: ${entry.default}")
                        appendLine("Reason: ${ex.message ?: ex.javaClass.simpleName}")
                    }
                }
                return
            }

            errors += buildString {
                appendLine("Missing required configuration.")
                appendLine("Key: ${entry.key}")
                appendLine("Environment variable: ${entry.envKey}")
                appendLine("Description: ${entry.description}")
                appendLine("Expected source: config file or environment variable.")
            }
            return
        }

        try {
            entry.loadFromConfig(rawValue.trim())
        } catch (ex: Exception) {
            errors += buildString {
                appendLine("Invalid configuration value.")
                appendLine("Key: ${entry.key}")
                appendLine("Environment variable: ${entry.envKey}")
                appendLine("Value: $rawValue")
                appendLine("Description: ${entry.description}")
                appendLine("Reason: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }
    }

    private fun mayCreateConfig() {
        if (configPath.exists()) return

        for (entry in entries) {
            if (System.getenv(entry.envKey) != null) {
                logger.info(
                    "Environment variable '{}' is provided. Skipping configuration file creation.",
                    entry.envKey
                )
                skipConfigCreation = true
                return
            }
        }

        logger.warn(
            "Configuration file does not exist. Creating default configuration at '{}'.",
            configPath.toAbsolutePath()
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
            configPath.toAbsolutePath().parent?.createDirectories()
            configPath.outputStream().bufferedWriter().use {
                it.write(defaultConfig)
            }

            logger.info(
                "Created default configuration at '{}'.",
                configPath.toAbsolutePath()
            )
        } catch (ex: IOException) {
            logger.warn(
                "Could not create configuration file at '{}': {}",
                configPath.toAbsolutePath(),
                ex.message
            )
        }
    }

    private fun loadProperties() {
        if (!configPath.exists()) {
            if (skipConfigCreation) {
                logger.info(
                    "Configuration file '{}' does not exist. Using environment variables and defaults.",
                    configPath.toAbsolutePath()
                )
            }
            return
        }

        try {
            configPath.inputStream().use(properties::load)
            logger.info("Loaded configuration from '{}'.", configPath.toAbsolutePath())
        } catch (ex: IOException) {
            throw IllegalStateException(
                "Failed to read configuration file '${configPath.toAbsolutePath()}': ${ex.message}",
                ex
            )
        }
    }

    private fun <I, O> ReadOnlyProperty<Config, I>.map(
        key: String,
        mapper: (I) -> O
    ): ConfigEntry<O> {
        val source = this as? ConfigEntry<I>
            ?: throw IllegalArgumentException(
                "Cannot map configuration '$key': the source is not a supported configuration entry."
            )

        val mappedEntry = ConfigEntry(
            key = key,
            default = null,
            description = "Mapped configuration derived from '$key'.",
            parser = { value -> mapper(source.parser(value)) }
        )

        mappedEntry.setMappingSource(source, mapper)
        entries += mappedEntry
        return mappedEntry
    }

    private class ConfigEntry<T>(
        val key: String,
        val default: T?,
        val description: String,
        val parser: (String) -> T
    ) : ReadOnlyProperty<Config, T> {
        private var internalValue: T? = null
        private var initialized = false
        private var mappingSource: ConfigEntry<Any?>? = null
        private var mapper: ((Any?) -> T)? = null
        private var mappingValidated = false

        val envKey = key.uppercase().replace('.', '_')

        fun setValue(value: T) {
            internalValue = value
            initialized = true
        }

        @Suppress("UNCHECKED_CAST")
        fun <I> setMappingSource(
            source: ConfigEntry<I>,
            mapper: (I) -> T
        ) {
            mappingSource = source as ConfigEntry<Any?>
            this.mapper = mapper as (Any?) -> T
        }

        fun loadFromEnvironment(value: String) {
            setValue(parser(value))
        }

        fun loadFromConfig(value: String) {
            setValue(parser(value))
        }

        fun loadDefault() {
            val defaultValue = default
                ?: throw IllegalStateException("No default value is defined.")
            setValue(defaultValue)
        }

        fun validateMappings() {
            if (mappingValidated) return

            val source = mappingSource
            if (source != null) {
                source.validateMappings()

                val sourceValue = source.getInitializedValue()
                val mapping = mapper
                    ?: throw IllegalStateException(
                        "Mapping function for configuration '$key' is missing."
                    )

                try {
                    setValue(mapping(sourceValue))
                } catch (ex: Exception) {
                    throw IllegalArgumentException(
                        "Failed to map configuration '$key'. Check the source value and mapping logic. Reason: ${ex.message ?: ex.javaClass.simpleName}",
                        ex
                    )
                }
            }

            mappingValidated = true
        }

        private fun getInitializedValue(): Any? {
            if (!initialized) {
                throw IllegalStateException(
                    "Configuration '$key' has not been initialized. Check that Config.init() completed successfully."
                )
            }
            return internalValue
        }

        override fun getValue(
            thisRef: Config,
            property: KProperty<*>
        ): T {
            if (!initialized) {
                throw IllegalStateException(
                    "Configuration property '${property.name}' (key '$key') was accessed before configuration initialization completed. Call Config.init() during application startup and ensure validation succeeds."
                )
            }

            @Suppress("UNCHECKED_CAST")
            return internalValue as T
        }
    }
}