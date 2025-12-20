package com.github.unscientificjszhai.geminiclijetbrainsideplugin.model

import kotlinx.serialization.Serializable

sealed class DiffParams {
    @Serializable
    data class DiffAcceptedParams(val filePath: String, val content: String) : DiffParams()

    @Serializable
    data class DiffRejectedParams(val filePath: String) : DiffParams()
}