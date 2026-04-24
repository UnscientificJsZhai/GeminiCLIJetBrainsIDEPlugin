package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.DiffNotificationParams
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.awt.Dimension
import java.io.File
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class DiffService(private val project: Project) : NotificationCallbackService<DiffNotificationParams>(), Disposable {
    override var notificationCallback: NotificationCallback<DiffNotificationParams>? = null
    private val openDiffDialogs = mutableMapOf<String, DialogWrapper>()

    private val logger = thisLogger()

    private fun runInEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeLater(action)
        }
    }

    override fun dispose() {
        runInEdt {
            if (project.isDisposed) return@runInEdt
            val dialogs = openDiffDialogs.values.toList()
            openDiffDialogs.clear()
            dialogs.forEach { it.close(DialogWrapper.CANCEL_EXIT_CODE) }
        }
    }

    fun openDiff(filePath: String, newContent: String): Boolean {
        val ioFile = File(filePath)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)

        runInEdt {
            if (project.isDisposed) return@runInEdt
            openDiffDialogs[filePath]?.close(DialogWrapper.CANCEL_EXIT_CODE)

            val dialog = if (virtualFile != null) {
                // Handle existing file diff
                val contentFactory = DiffContentFactory.getInstance()
                val originalContent = contentFactory.create(project, virtualFile)
                val newDiffContent = contentFactory.create(newContent)

                val request = SimpleDiffRequest(
                    "Review Changes: ${ioFile.name}",
                    originalContent,
                    newDiffContent,
                    "Current (Editable)",
                    "Proposed"
                )

                object : DialogWrapper(project) {
                    init {
                        init()
                        title = "Review Changes: ${ioFile.name}"
                        setOKButtonText("Accept")
                        setCancelButtonText("Reject")
                        if (!ApplicationManager.getApplication().isHeadlessEnvironment) {
                            window.minimumSize = Dimension(1000, 700)
                        }
                    }

                    override fun createCenterPanel(): JComponent {
                        val panel = DiffManager.getInstance().createRequestPanel(project, disposable, window)
                        panel.setRequest(request)
                        return panel.component
                    }

                    override fun doOKAction() {
                        val document = newDiffContent.document
                        val finalContent = document.text

                        notificationCallback?.callback(
                            "ide/diffAccepted",
                            DiffNotificationParams(filePath, finalContent)
                        )
                        openDiffDialogs.remove(filePath)
                        super.doOKAction()
                    }

                    override fun doCancelAction() {
                        notificationCallback?.callback("ide/diffRejected", DiffNotificationParams(filePath))
                        openDiffDialogs.remove(filePath)
                        super.doCancelAction()
                    }
                }
            } else {
                // Handle new file diff (single column view as per user choice)
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(ioFile.name)
                val document = EditorFactory.getInstance().createDocument(newContent)
                val editor = EditorFactory.getInstance().createEditor(document, project, fileType, false)

                object : DialogWrapper(project) {
                    init {
                        init()
                        title = "Review New File: ${ioFile.name}"
                        setOKButtonText("Accept")
                        setCancelButtonText("Reject")
                        if (!ApplicationManager.getApplication().isHeadlessEnvironment) {
                            window.minimumSize = Dimension(1000, 700)
                        }
                    }

                    override fun createCenterPanel(): JComponent {
                        return editor.component
                    }

                    override fun dispose() {
                        super.dispose()
                        EditorFactory.getInstance().releaseEditor(editor)
                    }

                    override fun doOKAction() {
                        val finalContent = editor.document.text
                        ApplicationManager.getApplication().runWriteAction {
                            try {
                                ioFile.parentFile?.mkdirs()
                                ioFile.writeText(finalContent)
                                // Refresh file system to show the new file
                                VfsUtil.markDirtyAndRefresh(false, true, true, ioFile)
                            } catch (e: Exception) {
                                logger.error("Error while creating new file", e)
                            }
                        }

                        notificationCallback?.callback(
                            "ide/diffAccepted",
                            DiffNotificationParams(filePath, finalContent)
                        )
                        openDiffDialogs.remove(filePath)
                        super.doOKAction()
                    }

                    override fun doCancelAction() {
                        notificationCallback?.callback("ide/diffRejected", DiffNotificationParams(filePath))
                        openDiffDialogs.remove(filePath)
                        super.doCancelAction()
                    }
                }
            }

            openDiffDialogs[filePath] = dialog
            if (!ApplicationManager.getApplication().isHeadlessEnvironment) {
                dialog.show()
            }
        }
        return true
    }

    fun closeDiff(filePath: String): Boolean {
        val dialog = openDiffDialogs[filePath] ?: return false
        runInEdt {
            if (!project.isDisposed) {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                openDiffDialogs.remove(filePath)
                notificationCallback?.callback("ide/diffRejected", DiffNotificationParams(filePath))
            }
        }
        return true
    }
}
