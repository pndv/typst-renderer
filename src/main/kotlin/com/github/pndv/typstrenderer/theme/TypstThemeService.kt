package com.github.pndv.typstrenderer.theme

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.messages.Topic

/**
 * Application-level service that acts as the single source of truth for theme changes.
 *
 * Subscribes to both [LafManagerListener] (UI LAF switches) and [EditorColorsManager.TOPIC]
 * (editor colour-scheme switches) and re-publishes on [TOPIC] so that every component
 * only needs one subscription instead of two.
 */
@Service(Service.Level.APP)
class TypstThemeService : Disposable {

    companion object {
        val TOPIC: Topic<TypstThemeListener> =
            Topic.create("Typst Theme", TypstThemeListener::class.java)

        val isDark: Boolean get() = EditorColorsManager.getInstance().isDarkEditor

        @JvmStatic
        fun getInstance(): TypstThemeService =
            ApplicationManager.getApplication().getService(TypstThemeService::class.java)
    }

    init {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)

        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            publish()
        })

        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { _ ->
            publish()
        })
    }

    private fun publish() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(TOPIC)
            .onThemeChanged(isDark)
    }

    override fun dispose() {}
}
