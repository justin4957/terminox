package com.terminox.agent

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.terminox.agent.config.AgentConfig
import com.terminox.agent.config.SecurityConfig
import com.terminox.agent.config.ServerConfig
import com.terminox.agent.server.AgentServer
import com.terminox.agent.server.ServerState
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Terminox Desktop Agent - Terminal session server for mobile clients.
 *
 * ## Usage
 * ```
 * terminox-agent [OPTIONS]
 *
 * Options:
 *   --host TEXT        Host to bind to (default: 0.0.0.0)
 *   --port INT         Port to listen on (default: 4076)
 *   --tls              Enable TLS encryption
 *   --cert PATH        Path to TLS certificate
 *   --key PATH         Path to TLS private key
 *   --mtls             Require mutual TLS (client certificates)
 *   --config PATH      Path to configuration file
 *   --version          Show version and exit
 *   -h, --help         Show help and exit
 * ```
 *
 * ## Features
 * - WebSocket-based terminal multiplexing
 * - TLS 1.3 encryption with optional mTLS
 * - Session persistence across restarts
 * - Native PTY, tmux, and screen backend support
 * - Resource limiting and rate limiting
 * - Service discovery via mDNS/Bonjour
 */
class TerminoxAgent : CliktCommand(
    name = "terminox-agent",
    help = "Terminox Desktop Agent - Terminal session server for mobile clients"
) {
    private val logger = LoggerFactory.getLogger(TerminoxAgent::class.java)

    private val host by option("--host", help = "Host to bind to")
        .default("0.0.0.0")

    private val port by option("--port", help = "Port to listen on")
        .int()
        .default(4076)

    private val enableTls by option("--tls", help = "Enable TLS encryption")
        .flag(default = false)

    private val certPath by option("--cert", help = "Path to TLS certificate")

    private val keyPath by option("--key", help = "Path to TLS private key")

    private val enableMtls by option("--mtls", help = "Require mutual TLS")
        .flag(default = false)

    private val configPath by option("--config", "-c", help = "Path to configuration file")

    private val showVersion by option("--version", "-v", help = "Show version")
        .flag(default = false)

    override fun run() {
        if (showVersion) {
            echo("Terminox Agent v1.0.0")
            return
        }

        logger.info("Starting Terminox Agent...")
        logger.info("Host: $host, Port: $port, TLS: $enableTls")

        // Build configuration
        val config = buildConfig()

        // Create and start server
        val server = AgentServer(config)

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutdown signal received")
            runBlocking {
                server.stop()
            }
        })

        runBlocking {
            try {
                server.start()

                // Wait for server to stop
                server.state.collect { state ->
                    when (state) {
                        ServerState.STOPPED -> {
                            logger.info("Server stopped")
                        }
                        ServerState.ERROR -> {
                            logger.error("Server encountered an error")
                            exitProcess(1)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to start server", e)
                exitProcess(1)
            }
        }
    }

    private fun buildConfig(): AgentConfig {
        // TODO: Load from configPath if provided

        val serverConfig = ServerConfig(
            host = host,
            port = port
        )

        val securityConfig = SecurityConfig(
            enableTls = enableTls,
            requireMtls = enableMtls,
            certificatePath = certPath,
            privateKeyPath = keyPath
        )

        return AgentConfig(
            server = serverConfig,
            security = securityConfig
        )
    }
}

fun main(args: Array<String>) = TerminoxAgent().main(args)
