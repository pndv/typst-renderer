package com.github.pndv.typstrenderer

/**
 * Compile-time constants that are NOT user-facing strings (and therefore don't go
 * through [TypstBundle]). Putting them here gives a single source of truth for IDs
 * used as keys/lookups across the plugin.
 */

/**
 * ID of the "Typst Output" tool window.
 *
 * Must stay in sync with the `id="..."` attribute on the `<toolWindow>` extension
 * in `META-INF/plugin.xml` — IntelliJ's tool-window registry uses this string as
 * the lookup key, not a localised name. Translating it would break
 * `ToolWindowManager.getToolWindow(...)` calls.
 *
 */
internal const val TYPST_OUTPUT_TOOL_WINDOW_ID = "Typst Output"

/**
 * ID of the notification group registered in `META-INF/plugin.xml` under
 * `<notificationGroup id="Typst" .../>`. Same constraint as
 * [TYPST_OUTPUT_TOOL_WINDOW_ID]: it's an identifier used by
 * `NotificationGroupManager.getNotificationGroup(...)`, not a user-facing label.
 */
internal const val TYPST_NOTIFICATION_GROUP_ID = "Typst"
