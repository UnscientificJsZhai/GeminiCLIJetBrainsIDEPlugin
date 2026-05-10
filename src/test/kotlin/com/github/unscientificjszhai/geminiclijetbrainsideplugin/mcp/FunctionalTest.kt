package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.IdeContext
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class ContextServiceTest : BasePlatformTestCase() {

    private lateinit var contextService: ContextService

    override fun setUp() {
        super.setUp()
        contextService = project.service<ContextService>()
    }

    fun testFileOpenedAndClosed() {
        val file1 = myFixture.addFileToProject("File1.kt", "fun main() {}").virtualFile
        myFixture.openFileInEditor(file1)

        // Capture the notification
        var updatedContext: IdeContext? = null
        contextService.registerNotificationCallback { _, context ->
            updatedContext = context
        }

        // Manually trigger update since we want to check state immediately
        contextService.sendContextUpdate()

        assertNotNull(updatedContext)
        val openFiles = updatedContext?.workspaceState?.openFiles ?: emptyList()
        assertTrue(openFiles.any { it.path.endsWith("File1.kt") })

        FileEditorManager.getInstance(project).closeFile(file1)
        contextService.sendContextUpdate()
        
        val openFilesAfterClose = updatedContext?.workspaceState?.openFiles ?: emptyList()
        assertFalse(openFilesAfterClose.any { it.path.endsWith("File1.kt") })
    }

    fun testSelectionAndCaret() {
        val content = "line1\nline2\nline3"
        val file = myFixture.addFileToProject("Test.kt", content).virtualFile
        myFixture.openFileInEditor(file)
        
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(content.indexOf("line2"))
        
        var updatedContext: IdeContext? = null
        contextService.registerNotificationCallback { _, context ->
            updatedContext = context
        }
        
        contextService.sendContextUpdate()
        
        val activeFile = updatedContext?.workspaceState?.openFiles?.find { it.isActive }
        assertNotNull(activeFile)
        assertEquals(1, activeFile?.cursor?.line) // line2 is the second line (index 1)

        // Test selection
        val startOffset = content.indexOf("line2")
        val endOffset = startOffset + "line2".length
        editor.selectionModel.setSelection(startOffset, endOffset)
        
        contextService.sendContextUpdate()
        val activeFileWithSelection = updatedContext?.workspaceState?.openFiles?.find { it.isActive }
        assertEquals("line2", activeFileWithSelection?.selectedText)
    }

    fun testSendContextUpdatePreservesRecentFileOrder() {
        val file1 = myFixture.addFileToProject("File1.kt", "fun one() {}").virtualFile
        val file2 = myFixture.addFileToProject("File2.kt", "fun two() {}").virtualFile
        var updatedContext: IdeContext? = null

        contextService.registerNotificationCallback { _, context ->
            updatedContext = context
        }

        myFixture.openFileInEditor(file1)
        contextService.sendContextUpdate()

        myFixture.openFileInEditor(file2)
        contextService.sendContextUpdate()

        myFixture.openFileInEditor(file1)
        myFixture.editor.caretModel.moveToOffset("fun ".length)
        contextService.sendContextUpdate()

        contextService.sendContextUpdate()

        val openFiles = updatedContext?.workspaceState?.openFiles ?: emptyList()
        assertTrue(openFiles.size >= 2)
        assertTrue(openFiles[0].path.endsWith("File1.kt"))
    }
}

class DiscoveryServiceTest : BasePlatformTestCase() {

    private lateinit var discoveryService: DiscoveryService

    override fun setUp() {
        super.setUp()
        discoveryService = project.service<DiscoveryService>()
    }

    fun testDiscoveryFileCreationAndCleanup() {
        val port = 12345
        discoveryService.startDiscovery(port)

        val pid = ProcessHandle.current().pid()
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "gemini/ide")
        val fileName = "gemini-ide-server-$pid-$port.json"
        val discoveryFile = File(tmpDir, fileName)

        assertTrue("Discovery file should exist at ${discoveryFile.absolutePath}", discoveryFile.exists())

        val content = discoveryFile.readText()
        assertTrue(content.contains("\"port\":$port"))
        assertTrue(content.contains(discoveryService.authToken))

        discoveryService.stopDiscovery()
        assertFalse("Discovery file should be deleted", discoveryFile.exists())
    }
}

class DiffServiceTest : BasePlatformTestCase() {
    private lateinit var diffService: DiffService

    override fun setUp() {
        super.setUp()
        diffService = project.service<DiffService>()
    }

    fun testOpenDiffNonExistentFile() {
        val filePath = "/non/existent/path"
        val success = diffService.openDiff(filePath, "new content")
        assertTrue("Should succeed for non-existent file", success)
        diffService.closeDiff(filePath)
    }
}

class McpServerAuthenticationTest : BasePlatformTestCase() {
    private lateinit var mcpServer: McpServer
    private lateinit var discoveryService: DiscoveryService

    override fun setUp() {
        super.setUp()
        mcpServer = project.service<McpServer>()
        discoveryService = project.service<DiscoveryService>()
        mcpServer.start()
    }

    override fun tearDown() {
        try {
            mcpServer.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun testRejectsRequestWithoutBearerToken() {
        val response = requestMcp()

        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.statusCode)
        assertEquals("Bearer", response.wwwAuthenticate)
    }

    fun testRejectsRequestWithInvalidBearerToken() {
        val response = requestMcp("Bearer wrong-token")

        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.statusCode)
        assertEquals("Bearer", response.wwwAuthenticate)
    }

    fun testAllowsRequestWithValidBearerToken() {
        val response = requestMcp("Bearer ${discoveryService.authToken}")

        assertFalse(response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED)
        assertNull(response.wwwAuthenticate)
    }

    fun testBearerSchemeIsCaseInsensitive() {
        val response = requestMcp("bearer ${discoveryService.authToken}")

        assertFalse(response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED)
        assertNull(response.wwwAuthenticate)
    }

    private fun requestMcp(authorization: String? = null): HttpResponse {
        var lastException: IOException? = null
        repeat(50) {
            try {
                return openMcpConnection(authorization).use { connection ->
                    HttpResponse(
                        statusCode = connection.responseCode,
                        wwwAuthenticate = connection.getHeaderField("WWW-Authenticate"),
                    )
                }
            } catch (e: IOException) {
                lastException = e
                Thread.sleep(100)
            }
        }

        throw AssertionError("MCP server did not respond on port ${mcpServer.port}", lastException)
    }

    private fun openMcpConnection(authorization: String?): HttpURLConnection {
        val connection = URI("http://127.0.0.1:${mcpServer.port}/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 500
        connection.readTimeout = 500
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        return connection
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        try {
            return block(this)
        } finally {
            disconnect()
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val wwwAuthenticate: String?,
    )
}
