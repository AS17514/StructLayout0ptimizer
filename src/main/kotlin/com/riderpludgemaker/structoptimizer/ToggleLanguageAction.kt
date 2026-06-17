package com.riderpludgemaker.structoptimizer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Toggles the plugin UI language between English and Chinese.
 */
class ToggleLanguageAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        Messages.toggle()
        val langName = if (Messages.currentLang == "zh" || Messages.currentLang == "auto") {
            Messages.get("lang_zh")
        } else {
            Messages.get("lang_en")
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StructLayoutOptimizer")
            .createNotification(
                Messages.get("toggle_lang_msg", langName),
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }
}
