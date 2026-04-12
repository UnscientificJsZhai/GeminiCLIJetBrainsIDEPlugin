package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.ContextFile
import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.CursorPosition
import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.IdeContext
import com.github.unscientificjszhai.geminiclijetbrainsideplugin.model.WorkspaceState
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val CLI_MAX_FILE_COUNT = 10
private const val CLI_DEBOUNCE_INTERVAL_MS = 50

@OptIn(FlowPreview::class)
@Service(Service.Level.PROJECT)
class ContextService(private val project: Project, private val scope: CoroutineScope) :
    NotificationCallbackService<IdeContext>(), Disposable {
    override var notificationCallback: NotificationCallback<IdeContext>? = null
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    private val updateFlow = MutableSharedFlow<Unit>(
        replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val openFiles: ArrayList<ContextFile> = ArrayList(CLI_MAX_FILE_COUNT)

    init {
        subscribeEditorEvents()

        scope.launch {
            readAction {
                synchronized(openFiles) {
                    openFiles.addAll(createAllContextFiles())
                }
            }

            updateFlow.debounce(CLI_DEBOUNCE_INTERVAL_MS.milliseconds).collect {
                sendContextUpdate()
            }
        }
    }

    private fun createAllContextFiles(): Sequence<ContextFile> {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openVirtualFiles = fileEditorManager.openFiles
        val selectedFiles = fileEditorManager.selectedFiles.toSet()

        return openVirtualFiles.run {
            asSequence().filter { it !in selectedFiles } + asSequence().filter { it in selectedFiles }
        }.map { file ->
            val editor = fileEditorManager.getSelectedEditor(file) as? TextEditor
            val isActive = file in selectedFiles
            file.toContextFile(editor?.editor, isActive)
        }
    }

    fun sendContextUpdate() {
        val notificationCallback = this.notificationCallback ?: return
        val currentFiles = synchronized(openFiles) { openFiles.toList() }.sortedByDescending { fileContext ->
            fileContext.timestamp
        }.take(CLI_MAX_FILE_COUNT)
        val context = IdeContext(
            workspaceState = WorkspaceState(
                openFiles = currentFiles, isTrusted = TrustedProjects.isProjectTrusted(project)
            )
        )
        notificationCallback.callback("ide/contextUpdate", context)
    }

    override fun dispose() {
        connection.disconnect()
        scope.cancel()
    }

    private fun emitUpdate() {
        scope.launch {
            updateFlow.emit(Unit)
        }
    }

    private fun subscribeEditorEvents() {
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                val editor = source.getSelectedEditor(file) as? TextEditor
                val contextFile = file.toContextFile(editor?.editor, isActive = false)
                synchronized(openFiles) {
                    openFiles.removeAll { it.path == file.path }
                    openFiles.add(contextFile)
                }
                emitUpdate()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                synchronized(openFiles) {
                    openFiles.removeAll { it.path == file.path }
                }
                emitUpdate()
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                val newFile = event.newFile
                synchronized(openFiles) {
                    val iterator = openFiles.listIterator()
                    while (iterator.hasNext()) {
                        val file = iterator.next()
                        if (file.path == newFile?.path) {
                            iterator.set(file.copy(isActive = true, timestamp = System.currentTimeMillis()))
                        } else if (file.isActive) {
                            iterator.set(file.copy(isActive = false, cursor = null, selectedText = null))
                        }
                    }
                }
                emitUpdate()
            }
        })

        VirtualFileManager.getInstance().addAsyncFileListener({
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    ApplicationManager.getApplication().invokeLater {
                        synchronized(openFiles) {
                            openFiles.clear()
                            openFiles.addAll(createAllContextFiles())
                        }
                        emitUpdate()
                    }
                }
            }
        }, this)

        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                updateFileState(event.editor)
            }
        }, this)

        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) {
                updateFileState(event.editor)
            }
        }, this)
    }

    private fun updateFileState(editor: Editor) {
        val virtualFile = editor.virtualFile ?: return
        synchronized(openFiles) {
            val index = openFiles.indexOfFirst { it.path == virtualFile.path }
            if (index != -1) {
                val current = openFiles[index]
                val updated = current.copy(
                    cursor = CursorPosition(
                        line = editor.caretModel.logicalPosition.line,
                        character = editor.caretModel.logicalPosition.column
                    ), selectedText = editor.selectionModel.selectedText,
                    timestamp = System.currentTimeMillis()
                )
                openFiles[index] = updated
                emitUpdate()
            }
        }
    }

    private fun VirtualFile.toContextFile(editor: Editor?, isActive: Boolean): ContextFile {
        return ContextFile(
            path = this.path, timestamp = System.currentTimeMillis(), isActive = isActive, cursor = editor?.let {
                CursorPosition(
                    line = it.caretModel.logicalPosition.line, character = it.caretModel.logicalPosition.column
                )
            }, selectedText = editor?.selectionModel?.selectedText
        )
    }
}
