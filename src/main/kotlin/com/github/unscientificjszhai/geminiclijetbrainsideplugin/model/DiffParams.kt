package com.github.unscientificjszhai.geminiclijetbrainsideplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class DiffNotificationParams(
    val filePath: String,
    val content: String? = null
)
