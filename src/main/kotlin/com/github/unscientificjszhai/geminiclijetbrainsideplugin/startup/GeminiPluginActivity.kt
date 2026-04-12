package com.github.unscientificjszhai.geminiclijetbrainsideplugin.startup

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp.McpServer
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class GeminiPluginActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.getService(McpServer::class.java).start()
    }
}