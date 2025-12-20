package com.github.unscientificjszhai.geminiclijetbrainsideplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class IdeContext(
    val workspaceState: WorkspaceState? = null
)

@Serializable
data class WorkspaceState(
    val openFiles: List<ContextFile>? = null,
    val isTrusted: Boolean
)

@Serializable
data class ContextFile(
    val path: String,
    val timestamp: Long,
    val isActive: Boolean = false,
    val cursor: CursorPosition? = null,
    val selectedText: String? = null
)

@Serializable
data class CursorPosition(
    val line: Int,
    val character: Int
)