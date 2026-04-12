package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.PROJECT)
class McpServer(project: Project, private val scope: CoroutineScope) : Disposable {
    private var serverInstance: EmbeddedServer<*, *>? = null
    private val discoveryService = project.service<DiscoveryService>()
    private val contextService = project.service<ContextService>()
    private val diffService = project.service<DiffService>()

    var port: Int = 0
        private set

    private var mcpServer: Server? = null

    private fun createMcpServer(implementation: Implementation) = Server(
        implementation, ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ).apply {
        addTool(
            name = "openDiff",
            description = "Opens a diff view",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("newContent", buildJsonObject { put("type", "string") })
                }, required = listOf("filePath", "newContent")
            ),
        ) { args ->
            val filePath = args.arguments?.get("filePath")?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("filePath required")
            val newContent = args.arguments?.get("newContent")?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("newContent required")

            val success = diffService.openDiff(filePath, newContent)
            CallToolResult(
                content = if (success) emptyList() else listOf(TextContent("")), isError = !success
            )
        }

        addTool(
            name = "closeDiff", description = "Closes a diff view", inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("suppressNotification", buildJsonObject { put("type", "boolean") })
                }, required = listOf("filePath")
            )
        ) { args ->
            val filePath = args.arguments?.get("filePath")?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("filePath required")

            val success = diffService.closeDiff(filePath)
            // Ideally read file content here but assuming success for now
            CallToolResult(
                content = if (success) listOf(TextContent("File content placeholder")) else listOf(TextContent("Failed to close diff")),
                isError = !success
            )
        }

        onConnect {
            scope.launch {
                // 如果不等待有大概率造成上下文变化前CLI收不到上下文
                delay(100.milliseconds)
                contextService.sendContextUpdate()
            }
        }
    }

    fun start() {
        if (serverInstance != null) return

        // Initialize MCP Server
        val implementation = Implementation(
            name = "JetBrains IDE Plugin", version = "1.0.0"
        )

        mcpServer = createMcpServer(implementation)

        // Setup notification listeners
        setupNotificationListeners()

        serverInstance = embeddedServer(CIO, port = 0) {
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowNonSimpleContentTypes = true
                allowHeader("Mcp-Session-Id")
                allowHeader("Mcp-Protocol-Version")
                allowHeader(HttpHeaders.Authorization)
                exposeHeader("Mcp-Session-Id")
                exposeHeader("Mcp-Protocol-Version")
            }
            install(ContentNegotiation) {
                json(McpJson)
            }

            install(Authentication) {
                bearer("Bearer") {
                    authenticate {
                        if (it.token == discoveryService.authToken) {
                            UserIdPrincipal("Bearer")
                        } else {
                            null
                        }
                    }
                }
            }
            mcpStreamableHttp {
                mcpServer!!
            }
        }.start(wait = false)

        runBlocking {
            port = serverInstance!!.engine.resolvedConnectors().first().port
            discoveryService.startDiscovery(port)
        }
    }

    private fun setupNotificationListeners() {
        contextService.registerNotificationCallback { method, context ->
            serverInstance?.application?.launch {
                mcpServer?.sessions?.forEach { (_, session) ->
                    session.transport?.send(
                        JSONRPCNotification(
                            method = Method.Custom(method).value,
                            params = McpJson.encodeToJsonElement(context)
                        )
                    )
                }
            }
        }

        diffService.registerNotificationCallback { method, params ->
            serverInstance?.application?.launch {
                mcpServer?.sessions?.forEach { (_, session) ->
                    session.transport?.send(
                        JSONRPCNotification(
                            method = Method.Custom(method).value,
                            params = McpJson.encodeToJsonElement(params)
                        )
                    )
                }
            }
        }
    }

    override fun dispose() {
        discoveryService.stopDiscovery()
        serverInstance?.stop(1000, 2000)
        serverInstance = null
    }
}
