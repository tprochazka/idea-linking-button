/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Code Reference Inserter.
 *
 * Code Reference Inserter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Code Reference Inserter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Code Reference Inserter. If not, see <https://www.gnu.org/licenses/>.
 */

package cz.atomsoft.ideaplugin.codereferenceinserter

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.frontend.TerminalInput
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.jediterm.terminal.TtyConnector
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Component
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

/** Describes which terminal selection source is safe to use for an insertion. */
internal enum class TerminalSelectionRoute {
    SELECTED_CONTENT,
    SINGLETON,
    NONE,
}

/**
 * Chooses selected-content routing before any singleton fallback.
 *
 * A present but unmatched selected content deliberately returns [TerminalSelectionRoute.NONE]
 * so text cannot leak into another terminal widget.
 */
internal fun terminalSelectionRoute(
    selectedContentAvailable: Boolean,
    selectedContentMatchCount: Int,
    singletonMatchCount: Int,
): TerminalSelectionRoute = when {
    selectedContentAvailable && selectedContentMatchCount > 0 -> TerminalSelectionRoute.SELECTED_CONTENT
    selectedContentAvailable -> TerminalSelectionRoute.NONE
    singletonMatchCount == 1 -> TerminalSelectionRoute.SINGLETON
    else -> TerminalSelectionRoute.NONE
}

/**
 * Selects one terminal candidate, using focus only when a container has multiple candidates.
 * Returns `null` when the focused state is absent or ambiguous.
 */
internal fun <T> selectFocusedTerminal(
    candidates: List<T>,
    isFocused: (T) -> Boolean,
): T? {
    if (candidates.size == 1) return candidates.single()
    return candidates.filter(isFocused).singleOrNull()
}

/**
 * Executes one write followed by best-effort activation.
 *
 * A successful write remains successful when activation fails. Cancellation is rethrown and
 * write failures are reported through [onWriteFailure] without trying another write path.
 */
internal fun executeTerminalInsertion(
    text: String,
    write: (String) -> Unit,
    activate: () -> Unit,
    onActivationFailure: (Throwable) -> Unit,
    onWriteFailure: (Throwable) -> Unit,
): Boolean {
    return try {
        write(text)
        try {
            activate()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onActivationFailure(e)
        }
        true
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onWriteFailure(e)
        false
    }
}

/** Writes to a classic terminal connector, returning `false` when it is unavailable. */
internal fun writeTerminalConnector(connector: TtyConnector?, text: String): Boolean {
    connector ?: return false
    connector.write(text)
    return true
}

/** Returns whether [target] exposes a public one-String method with [methodName]. */
internal fun hasPublicStringMethod(target: Any, methodName: String): Boolean =
    target.javaClass.methods.any { method ->
        method.name == methodName &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == String::class.java
    }

/** Invokes one public one-String method, returning `false` when it is unavailable. */
internal fun invokeReflectiveStringMethod(target: Any, methodName: String, text: String): Boolean {
    val method = target.javaClass.methods.firstOrNull { candidate ->
        candidate.name == methodName &&
            candidate.parameterCount == 1 &&
            candidate.parameterTypes[0] == String::class.java
    } ?: return false
    try {
        method.invoke(target, text)
    } catch (e: InvocationTargetException) {
        throw (e.targetException ?: e)
    }
    return true
}

/**
 * Inserts caller-provided text into the selected JetBrains Terminal widget without executing it.
 * Clipboard fallback and user notification remain the responsibility of the caller.
 */
@Service(Service.Level.PROJECT)
class TerminalTextInserter(private val project: Project) {

    private val logger = logger<TerminalTextInserter>()
    private val debugLogging = isDebugLoggingEnabled()
    var lastLookupSummary: String = "Terminal lookup has not run yet."
        private set

    /**
     * Writes [text] once to the selected terminal and then requests focus activation.
     *
     * `true` means the terminal input accepted the write; focus activation is best effort.
     * The caller can use `false` to perform its clipboard fallback. Cancellation exceptions
     * are propagated to the caller.
     */
    fun insertIntoSelectedTerminal(text: String): Boolean {
        return try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val target = findTarget(terminalManager)
            if (target == null) {
                lastLookupSummary = "No selected terminal input. $lastLookupSummary"
                return false
            }

            executeTerminalInsertion(
                text = text,
                write = { insertedText ->
                    // A target has exactly one write path. Retrying another path after an exception
                    // could submit the same text twice when the first path already reached the PTY.
                    target.write(insertedText)
                    lastLookupSummary = "Inserted into ${target.description}. $lastLookupSummary"
                },
                activate = { activateTerminal(target) },
                onActivationFailure = { e ->
                    // Focus is best effort and must not turn a successful write into a
                    // reported failure that could make the caller retry the insertion.
                    logger.warn("Failed to activate terminal after text insertion", e)
                },
                onWriteFailure = { e ->
                    logger.warn("Failed to insert text into selected terminal", e)
                    lastLookupSummary =
                        "Exception during terminal lookup/write: ${e.javaClass.simpleName}: ${e.message}"
                },
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to find selected terminal", e)
            lastLookupSummary = "Exception during terminal lookup: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /** Resolves a target from the selected terminal content or a safe singleton fallback. */
    private fun findTarget(terminalManager: TerminalToolWindowManager): TerminalTarget? {
        val toolWindow = terminalToolWindow(terminalManager)
        if (toolWindow != null) {
            val selectedContent = toolWindow.contentManager.selectedContent
            if (selectedContent != null) {
                val target = findTargetForSelectedContent(terminalManager, toolWindow, selectedContent)
                lastLookupSummary =
                    "toolWindow=true, selectedContent=${contentName(selectedContent)}, " +
                        "target=${target?.description ?: "none"}"
                return target
            }
        }

        // There is no selected tab to identify a terminal. A singleton is safe only when
        // the manager itself exposes exactly one widget; never search unrelated tool windows.
        val modernWidgets = terminalManager.terminalWidgets.toList()
        val legacyWidgets = terminalManager.widgets.toList()
        lastLookupSummary =
            "toolWindow=${toolWindow != null}, selectedContent=none, " +
                "modern=${modernWidgets.size}, legacy=${legacyWidgets.size}"

        return when (terminalSelectionRoute(false, 0, singletonMatchCount(modernWidgets, legacyWidgets))) {
            TerminalSelectionRoute.SINGLETON -> if (modernWidgets.size == 1) {
                widgetTarget(modernWidgets.single(), toolWindow)
            } else {
                legacyWidgetTarget(legacyWidgets.single(), toolWindow)
            }
            else -> null
        }
    }

    private fun singletonMatchCount(
        modernWidgets: List<TerminalWidget>,
        legacyWidgets: List<JBTerminalWidget>,
    ): Int = when {
        modernWidgets.isNotEmpty() -> if (modernWidgets.size == 1) 1 else 0
        legacyWidgets.size == 1 -> 1
        else -> 0
    }

    /** Resolves only widgets belonging to [selectedContent], including focused split routing. */
    private fun findTargetForSelectedContent(
        terminalManager: TerminalToolWindowManager,
        toolWindow: ToolWindow,
        selectedContent: Content,
    ): TerminalTarget? {
        // Reworked Terminal (used by recent Android Studio builds) is not represented by
        // TerminalToolWindowManager. Its public frontend tabs manager owns the selected view.
        // Keep this bridge reflective because the plugin is compiled against older IDE APIs.
        val reworkedView = findReworkedTerminalViewBySelectedContent(selectedContent)
        debug {
            "selected terminal content=${contentName(selectedContent)}, " +
                "reworkedView=${reworkedView?.javaClass?.name ?: "none"}"
        }
        reworkedViewTarget(reworkedView, toolWindow)?.let { return it }

        val modernMatches = terminalManager.terminalWidgets
            .filter { widget -> terminalManager.getContainer(widget)?.content == selectedContent }
            .toList()

        if (modernMatches.isNotEmpty()) {
            // A split container has one Content for multiple widgets. The focused widget
            // is the only reliable selection signal; without it the target is ambiguous.
            return selectFocusedTerminal(modernMatches) { it.hasFocus() }
                ?.let { widgetTarget(it, toolWindow) }
        }

        // This is the supported legacy lookup for the selected terminal tab. It does not
        // inspect component trees or private state and cannot select another tab.
        val legacyMatch = TerminalToolWindowManager.getWidgetByContent(selectedContent)
        val route = terminalSelectionRoute(
            selectedContentAvailable = true,
            selectedContentMatchCount = if (legacyMatch == null) 0 else 1,
            singletonMatchCount = 0,
        )
        if (route != TerminalSelectionRoute.SELECTED_CONTENT) {
            return null
        }
        legacyMatch?.let {
            return legacyWidgetTarget(it, toolWindow)
        }

        return null
    }

    private fun widgetTarget(widget: TerminalWidget, toolWindow: ToolWindow?): TerminalTarget =
        TerminalTarget(
            description = widget.javaClass.name,
            toolWindow = toolWindow,
            focus = { widget.requestFocus() },
            write = { text -> writeText(widget, text) },
        )

    private fun legacyWidgetTarget(widget: JBTerminalWidget, toolWindow: ToolWindow?): TerminalTarget =
        widgetTarget(widget.asNewWidget(), toolWindow)

    private fun terminalToolWindow(terminalManager: TerminalToolWindowManager): ToolWindow? =
        terminalManager.toolWindow
            ?: ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)

    /** Finds the selected frontend terminal view without depending on its optional classes. */
    private fun findReworkedTerminalViewBySelectedContent(selectedContent: Content): Any? {
        return try {
            val managerClass = Class.forName(REWORKED_TABS_MANAGER_CLASS)
            val getInstance = managerClass.methods.firstOrNull { method ->
                method.name == "getInstance" &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0].isAssignableFrom(Project::class.java)
            } ?: return null
            val manager = try {
                getInstance.invoke(null, project)
            } catch (e: InvocationTargetException) {
                throw (e.targetException ?: e)
            } ?: return null
            val tabs = invokeNoArg(manager, "getTabs") as? Iterable<*> ?: return null
            for (tab in tabs) {
                if (invokeNoArg(tab, "getContent") === selectedContent) {
                    return invokeNoArg(tab, "getView")
                }
            }
            null
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            debug { "Reworked terminal lookup unavailable: ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }

    /** Creates a target for the frontend TerminalView's public sendText API. */
    private fun reworkedViewTarget(view: Any?, toolWindow: ToolWindow?): TerminalTarget? {
        view ?: return null
        if (!hasPublicStringMethod(view, "sendText")) return null
        val focusComponent = try {
            invokeNoArg(view, "getPreferredFocusableComponent") as? Component
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        return TerminalTarget(
            description = view.javaClass.name,
            toolWindow = toolWindow,
            focus = { focusComponent?.requestFocus() },
            write = { text ->
                if (!invokeReflectiveStringMethod(view, "sendText", text)) {
                    throw IllegalStateException("Reworked terminal view has no sendText method")
                }
            },
        )
    }

    /** Writes through exactly one reworked-input or classic-connector path. */
    private fun writeText(widget: TerminalWidget, text: String) {
        findReworkedTerminalInput(widget)?.let { input ->
            input.sendString(text)
            return
        }

        // The classic terminal exposes the PTY through the public TerminalWidget API.
        // This writes the supplied characters only; it never executes or appends a newline.
        if (!writeTerminalConnector(widget.ttyConnector, text)) {
            throw IllegalStateException("Terminal connector is unavailable")
        }
    }

    /** Looks up the reworked terminal input through its focused component data context. */
    private fun findReworkedTerminalInput(widget: TerminalWidget): TerminalInput? {
        val focusComponent = widget.preferredFocusableComponent
        val dataContext = DataManager.getInstance().getDataContext(focusComponent)
        return dataContext.getData(TERMINAL_INPUT_DATA_KEY) as? TerminalInput
    }

    private fun invokeNoArg(target: Any?, methodName: String): Any? {
        target ?: return null
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterCount == 0
        } ?: return null
        return try {
            method.invoke(target)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    /** Activates the terminal and focuses its widget when a tool window is available. */
    private fun activateTerminal(target: TerminalTarget) {
        target.toolWindow?.activate({ target.focus() }, true) ?: target.focus()
    }

    private fun contentName(content: Content): String =
        content.displayName.ifBlank { "unnamed" }

    private inline fun debug(message: () -> String) {
        if (debugLogging) logger.info(message())
    }

    /** A resolved widget plus the single write and focus operations allowed for it. */
    private data class TerminalTarget(
        val description: String,
        val toolWindow: ToolWindow?,
        val focus: () -> Unit,
        val write: (String) -> Unit,
    )

    private companion object {
        private const val REWORKED_TABS_MANAGER_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"

        // TerminalInput is installed by the reworked terminal on its focused output editor.
        // DataContext is the terminal's supported lookup boundary; no private fields or
        // arbitrary user-data maps are traversed.
        val TERMINAL_INPUT_DATA_KEY: DataKey<Any> = DataKey.create("TerminalInput")
    }
}
