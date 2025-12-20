package com.github.unscientificjszhai.geminiclijetbrainsideplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class DiscoveryInfo(
    val port: Int,
    val workspacePath: String,
    val authToken: String,
    val ideInfo: IdeInfo
)

@Serializable
data class IdeInfo(
    val name: String,
    val displayName: String,
)