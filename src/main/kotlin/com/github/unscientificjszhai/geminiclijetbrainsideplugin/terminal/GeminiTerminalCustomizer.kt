package com.github.unscientificjszhai.geminiclijetbrainsideplugin.terminal

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp.McpServer
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

class GeminiTerminalCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<out String>,
        envs: MutableMap<String, String>
    ): Array<out String> {
        val mcpServer = project.service<McpServer>()
        val port = mcpServer.port

        if (port in 1..65535) {
            envs["GEMINI_CLI_IDE_SERVER_PORT"] = port.toString()
            thisLogger().info("Injected GEMINI_CLI_IDE_SERVER_PORT=$port into terminal environment")
        } else {
            thisLogger().warn("McpServer port is null, cannot inject environment variable")
        }

        return command
    }
}
