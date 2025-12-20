package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.DiscoveryInfo
import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.IdeInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.*

@Service(Service.Level.PROJECT)
class DiscoveryService(private val project: Project) : Disposable {
    private var currentPort: Int? = null
    val authToken = generateAuthToken()
    private val pid = ProcessHandle.current().pid()

    fun startDiscovery(port: Int) {
        thisLogger().warn("Starting discovery for project: ${project.name}")
        currentPort = port
        updateDiscoveryFile()
    }

    fun stopDiscovery() {
        if (currentPort != null) {
            val file = getDiscoveryFile(currentPort!!)
            if (file.exists()) {
                file.delete()
            }
            currentPort = null
        }
    }

    private fun updateDiscoveryFile() {
        thisLogger().warn("Updating discovery for project: ${project.name}")
        val port = currentPort ?: return

        val workspacePath = ProjectRootManager.getInstance(project).contentRoots.let { contentRoots ->
            if (contentRoots.isNotEmpty()) {
                contentRoots.joinToString(File.pathSeparator) { it.path }
            } else {
                project.basePath ?: ""
            }
        }

        val file = getDiscoveryFile(port)
        file.parentFile.mkdirs()

        val info = DiscoveryInfo(
            port = port,
            workspacePath = workspacePath,
            authToken = authToken,
            ideInfo = IdeInfo("jetbrains", "JetBrains IDE")
        )

        val json = Json { prettyPrint = false }
        thisLogger().warn("Discovery file: $file")
        file.writeText(json.encodeToString(info))
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.deleteOnExit()
    }

    private fun getDiscoveryFile(port: Int): File {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "gemini/ide")
        val fileName = "gemini-ide-server-$pid-$port.json"
        return File(tmpDir, fileName)
    }

    private fun generateAuthToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    override fun dispose() {
        stopDiscovery()
    }
}
