package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.DiffNotificationParams
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Dimension
import java.io.File
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class DiffService(private val project: Project) : NotificationCallbackService<DiffNotificationParams>(), Disposable {
    override var notificationCallback: NotificationCallback<DiffNotificationParams>? = null
    private val openDiffDialogs = mutableMapOf<String, DialogWrapper>()

    private val logger = thisLogger()

    override fun dispose() {
        ApplicationManager.getApplication().invokeLater {
            val dialogs = openDiffDialogs.values.toList()
            dialogs.forEach { it.close(DialogWrapper.CANCEL_EXIT_CODE) }
            openDiffDialogs.forEach { (key, _) ->
                notificationCallback?.callback("ide/diffRejected", DiffNotificationParams(key))
            }
            openDiffDialogs.clear()
        }
    }

    fun openDiff(filePath: String, newContent: String): Boolean {
        val ioFile = File(filePath)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile) ?: return false

        ApplicationManager.getApplication().invokeLater {
            openDiffDialogs[filePath]?.close(DialogWrapper.CANCEL_EXIT_CODE)

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

            val dialog = object : DialogWrapper(project) {
                init {
                    init()
                    title = "Review Changes: ${ioFile.name}"
                    setOKButtonText("Accept")
                    setCancelButtonText("Reject")
                    // Make it big enough
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

                    ApplicationManager.getApplication().runWriteAction {
                        try {
                            val fileDoc = FileDocumentManager.getInstance().getDocument(virtualFile)
                            if (fileDoc != null) {
                                fileDoc.setText(finalContent)
                                FileDocumentManager.getInstance().saveDocument(fileDoc)
                            }
                        } catch (e: Exception) {
                            logger.error("Error while writing document", e)
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

            openDiffDialogs[filePath] = dialog
            if (!ApplicationManager.getApplication().isHeadlessEnvironment) {
                dialog.show()
            }
        }
        return true
    }

    fun closeDiff(filePath: String): Boolean {
        val dialog = openDiffDialogs[filePath] ?: return false
        ApplicationManager.getApplication().invokeLater {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            openDiffDialogs.remove(filePath)
            notificationCallback?.callback("ide/diffRejected", DiffNotificationParams(filePath))
        }
        return true
    }
}
