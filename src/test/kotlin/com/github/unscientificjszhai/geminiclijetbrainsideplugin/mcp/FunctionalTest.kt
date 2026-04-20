package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.IdeContext
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

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
        val success = diffService.openDiff("/non/existent/path", "new content")
        assertFalse("Should fail for non-existent file", success)
    }
}
