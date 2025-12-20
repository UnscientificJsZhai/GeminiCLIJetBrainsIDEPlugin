package com.github.unscientificjszhai.geminiclijetbrainsideplugin.mcp

abstract class NotificationCallbackService<T> {
    abstract var notificationCallback: NotificationCallback<T>?

    open fun registerNotificationCallback(callback: NotificationCallback<T>) {
        notificationCallback = callback
    }
}

fun interface NotificationCallback<T> {
    fun callback(method: String, params: T)
}