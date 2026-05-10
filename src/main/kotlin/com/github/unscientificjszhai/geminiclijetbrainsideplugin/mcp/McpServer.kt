package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.BindException
import java.net.ServerSocket
import java.text.MessageFormat
import java.util.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
class McpServer(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private var serverInstance: EmbeddedServer<*, *>? = null
    private val discoveryService = project.service<DiscoveryService>()
    private val contextService = project.service<ContextService>()
    private val diffService = project.service<DiffService>()

    private val logger = thisLogger()

    var port: Int = 0
        private set

    private var mcpServer: Server? = null

    private var preReservedSocket: ServerSocket? = null

    private val bearerAuthenticationHook =
        object : Hook<suspend PipelineContext<Unit, PipelineCall>.(PipelineCall) -> Unit> {
            override fun install(
                pipeline: ApplicationCallPipeline,
                handler: suspend PipelineContext<Unit, PipelineCall>.(PipelineCall) -> Unit,
            ) {
                pipeline.intercept(ApplicationCallPipeline.Plugins) {
                    handler(call)
                }
            }
        }

    private val bearerAuthenticationPlugin = createApplicationPlugin("McpBearerAuthentication") {
        on(bearerAuthenticationHook) { call ->
            if (call.hasValidBearerToken()) return@on

            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respond(HttpStatusCode.Unauthorized)
            finish()
        }
    }

    init {
        try {
            val socket = ServerSocket(0)
            preReservedSocket = socket
            port = socket.localPort
        } catch (e: Exception) {
            logger.warn("Failed to pre-reserve port", e)
        }
    }

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

    @Synchronized
    fun start() {
        if (serverInstance != null) return

        // Initialize MCP Server
        val implementation = Implementation(
            name = "JetBrains IDE Plugin", version = "1.0.2"
        )

        mcpServer = createMcpServer(implementation)

        // Setup notification listeners
        setupNotificationListeners()

        // Close pre-reserved socket right before starting Ktor
        preReservedSocket?.close()
        preReservedSocket = null

        val server = createServer(port)
        serverInstance = server
        server.start(wait = false)

        discoveryService.startDiscovery(port)
    }

    @Volatile
    private var retriedCreateServer = false
    private val bindExceptionHandler = CoroutineExceptionHandler { _, exception ->
        if (exception is BindException && !retriedCreateServer) {
            retriedCreateServer = true
            this.scope.launch {
                logger.warn("Failed to bind to pre-reserved port $port, retrying with port 0")
                discoveryService.stopDiscovery()
                serverInstance?.stop()
                val oldPort = port
                val server = createServer(0, retry = false)
                serverInstance = server
                server.start(wait = false)
                port = server.engine.resolvedConnectors().first().port
                discoveryService.startDiscovery(port)
                notifyPreRegisteredPortBindingFailed(oldPort, port)
            }
        }
    }

    private fun createServer(targetPort: Int, retry: Boolean = true): EmbeddedServer<*, *> = scope.embeddedServer(
        CIO, port = targetPort, parentCoroutineContext = if (retry) bindExceptionHandler else EmptyCoroutineContext,
    ) {
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

        install(bearerAuthenticationPlugin)
        mcpStreamableHttp {
            mcpServer!!
        }
        launch {
            while (true) {
                delay(30.seconds)
                mcpServer?.sessions?.forEach { (_, session) ->
                    try {
                        session.transport?.send(
                            JSONRPCNotification(method = Method.Custom("heartbeat").value, params = null)
                        )
                    } catch (e: Exception) {
                        logger.warn("Sending heartbeat error", e)
                    }
                }
            }
        }
    }

    private fun notifyPreRegisteredPortBindingFailed(oldPort: Int, newPort: Int) {
        logger.warn("Port changed from $oldPort to $newPort due to binding failure")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Gemini CLI Companion")
            .createNotification(
                message("notification.port.binding.failed.title"),
                message("notification.port.binding.failed.content", oldPort, newPort),
                NotificationType.ERROR
            )
            .notify(project)
    }

    private fun message(key: String, vararg params: Any): String =
        MessageFormat.format(ResourceBundle.getBundle("messages.PortBindingNotification").getString(key), *params)

    private fun ApplicationCall.hasValidBearerToken(): Boolean {
        val authorizationHeader = request.headers[HttpHeaders.Authorization] ?: return false
        val authHeader = runCatching { parseAuthorizationHeader(authorizationHeader) }.getOrNull()
        return authHeader is HttpAuthHeader.Single &&
                authHeader.authScheme.equals("Bearer", ignoreCase = true) &&
                authHeader.blob == discoveryService.authToken
    }

    private fun setupNotificationListeners() {
        contextService.registerNotificationCallback { method, context ->
            val server = serverInstance
            if (server != null) {
                try {
                    server.application.launch {
                        mcpServer?.sessions?.forEach { (_, session) ->
                            try {
                                session.transport?.send(
                                    JSONRPCNotification(
                                        method = Method.Custom(method).value,
                                        params = McpJson.encodeToJsonElement(context)
                                    )
                                )
                            } catch (e: Exception) {
                                logger.warn("Sending context message error", e)
                            }
                        }
                    }
                } catch (_: IllegalStateException) {
                    // Ignore if server is already stopped
                }
            }
        }

        diffService.registerNotificationCallback { method, params ->
            val server = serverInstance
            if (server != null) {
                try {
                    server.application.launch {
                        mcpServer?.sessions?.forEach { (_, session) ->
                            try {
                                session.transport?.send(
                                    JSONRPCNotification(
                                        method = Method.Custom(method).value,
                                        params = McpJson.encodeToJsonElement(params)
                                    )
                                )
                            } catch (e: Exception) {
                                logger.warn("Sending diff message error", e)
                            }
                        }
                    }
                } catch (_: IllegalStateException) {
                    // Ignore if server is already stopped
                }
            }
        }
    }

    override fun dispose() {
        contextService.notificationCallback = null
        diffService.notificationCallback = null
        discoveryService.stopDiscovery()
        preReservedSocket?.close()
        val server = serverInstance
        serverInstance = null
        server?.stop(1000, 2000)
    }
}
