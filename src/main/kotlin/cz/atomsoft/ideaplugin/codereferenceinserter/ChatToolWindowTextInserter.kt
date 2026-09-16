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

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Finds and writes into chat inputs of IntelliJ's built-in AI Assistant/ACP, GitHub Copilot,
 * or Android Studio Gemini tool windows, with a conservative generic Swing text-input fallback
 * for other tool windows.
 * None of these chat implementations is a compile-time dependency, so detection uses the
 * already-loaded Swing component tree or a narrow reflective bridge, in the same
 * compatibility-first spirit as [TerminalTextInserter].
 */
@Service(Service.Level.PROJECT)
class ChatToolWindowTextInserter(private val project: Project) {

    private val logger = logger<ChatToolWindowTextInserter>()
    private val debugLogging = isDebugLoggingEnabled()
    var lastLookupSummary: String = "Chat lookup has not run yet."
        private set

    internal fun findTargetInContent(toolWindow: ToolWindow, content: Content): TextInsertTarget? {
        val surface = classifyChatSurface(
            toolWindow.id,
            runCatching { toolWindow.stripeTitle }.getOrDefault(""),
            containsClassPrefix(content.component, AI_ASSISTANT_CLASS_PREFIX),
        )
        debug { "lookup toolWindow=${toolWindow.id} content=${content.displayName} surface=$surface" }
        val specializedTarget = when (surface) {
            ChatSurface.AI_ASSISTANT -> findWritableEditorDescendant(content.component)?.let { editor ->
                TextInsertTarget(
                    description = "AI Assistant chat input (${toolWindow.id})",
                    toolWindow = toolWindow,
                    focus = { editor.contentComponent.requestFocus() },
                    write = { text -> insertIntoEditor(editor, text) },
                )
            }
            ChatSurface.COPILOT -> findWritableCopilotInput(content.component)?.let { input ->
                TextInsertTarget(
                    description = "GitHub Copilot chat input (${toolWindow.id})",
                    toolWindow = toolWindow,
                    focus = { input.requestFocusInWindow() },
                    write = { text -> insertIntoTextComponent(input, text) },
                )
            }
            ChatSurface.GEMINI -> GeminiToolWindowBridge.findTarget(content)?.let { gemini ->
                TextInsertTarget(
                    description = "Android Studio Gemini chat input (${toolWindow.id})",
                    toolWindow = toolWindow,
                    focus = { GeminiToolWindowBridge.focus(gemini) },
                    write = { text -> GeminiToolWindowBridge.insert(gemini, text) },
                )
            }
            null -> null
        }
        val target = specializedTarget ?: if (shouldUseGenericTextFallback(surface)) {
            findWritableGenericTextInput(content.component)?.let { input ->
                TextInsertTarget(
                    description = "Generic Swing text input (${toolWindow.id})",
                    toolWindow = toolWindow,
                    focus = { input.requestFocusInWindow() },
                    write = { text -> insertIntoTextComponent(input, text) },
                )
            }
        } else {
            null
        }
        if (target == null) {
            lastLookupSummary = if (surface == null) {
                "toolWindow=${toolWindow.id} has no recognized chat or unique writable text input."
            } else {
                "toolWindow=${toolWindow.id} matched $surface but no writable input was found."
            }
            return null
        }

        lastLookupSummary = when {
            specializedTarget != null -> "toolWindow=${toolWindow.id} matched $surface chat input."
            else -> "toolWindow=${toolWindow.id} matched a unique generic Swing text input."
        }
        return target
    }

    private fun containsClassPrefix(root: Component, prefix: String): Boolean {
        if (root.javaClass.name.startsWith(prefix)) {
            return true
        }
        if (root is Container) {
            for (child in root.components) {
                if (containsClassPrefix(child, prefix)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findWritableEditorDescendant(root: Component): Editor? {
        return runCatching {
            EditorFactory.getInstance().allEditors.firstOrNull { editor ->
                !editor.isDisposed &&
                    !editor.isViewer &&
                    editor.document.isWritable &&
                    runCatching { SwingUtilities.isDescendingFrom(editor.contentComponent, root) }.getOrDefault(false)
            }
        }.getOrElse {
            logger.warn("Failed to search for chat input editor", it)
            null
        }
    }

    private fun findWritableCopilotInput(root: Component): JTextComponent? {
        return findTextComponentDescendant(root) { component ->
            component.javaClass.name.startsWith(COPILOT_INPUT_CLASS_PREFIX)
        }
    }

    private fun findWritableGenericTextInput(root: Component): JTextComponent? {
        return findGenericTextInput(
            root,
            KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
        )
    }

    internal fun findGenericTextInput(root: Component, focusOwner: Component?): JTextComponent? {
        val candidates = mutableListOf<JTextComponent>()
        collectWritableTextComponents(root, candidates)
        val selected = selectGenericTextInput(candidates, focusOwner)
        debug {
            "generic Swing candidates=${candidates.size}, " +
                "focusOwner=${focusOwner?.javaClass?.name ?: "none"}, " +
                "selected=${selected?.javaClass?.name ?: "none"}"
        }
        return selected
    }

    private fun collectWritableTextComponents(root: Component, result: MutableList<JTextComponent>) {
        if (!root.isVisible) return
        if (root is JTextComponent && isWritableTextComponent(root)) {
            result += root
        }
        if (root is Container) {
            root.components.forEach { child -> collectWritableTextComponents(child, result) }
        }
    }

    private fun findTextComponentDescendant(
        root: Component,
        predicate: (JTextComponent) -> Boolean,
    ): JTextComponent? {
        if (root is JTextComponent && root.isShowing && isWritableTextComponent(root) && predicate(root)) {
            return root
        }
        if (root is Container) {
            for (child in root.components) {
                findTextComponentDescendant(child, predicate)?.let { return it }
            }
        }
        return null
    }

    private fun isWritableTextComponent(component: JTextComponent): Boolean =
        component.isEnabled && component.isEditable && component.document != null

    private fun insertIntoEditor(editor: Editor, text: String): Boolean {
        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                EditorModificationUtil.insertStringAtCaret(editor, text, false, true)
            }
            true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to insert text into chat input", e)
            false
        }
    }

    private fun insertIntoTextComponent(component: JTextComponent, text: String): Boolean {
        return try {
            if (!SwingUtilities.isEventDispatchThread()) {
                logger.warn("Swing text input insertion was requested off the EDT")
                return false
            }
            component.replaceSelection(text)
            true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to insert text into Swing text input", e)
            false
        }
    }

    private inline fun debug(message: () -> String) {
        if (debugLogging) logger.info(message())
    }
}

/** Returns a single generic text input, or the uniquely focused one when several are present. */
internal fun selectGenericTextInput(
    candidates: List<JTextComponent>,
    focusOwner: Component?,
): JTextComponent? {
    if (candidates.size == 1) return candidates.single()
    return candidates.singleOrNull { candidate ->
        candidate === focusOwner || focusOwner?.let { SwingUtilities.isDescendingFrom(it, candidate) } == true
    }
}

internal enum class ChatSurface {
    AI_ASSISTANT,
    COPILOT,
    GEMINI,
}

internal const val COPILOT_CHAT_TOOL_WINDOW_ID = "GitHub Copilot Chat"
internal const val GEMINI_CHAT_TOOL_WINDOW_ID = "StudioBot"
private const val AI_ASSISTANT_CLASS_PREFIX = "com.intellij.ml.llm."
private const val COPILOT_INPUT_CLASS_PREFIX = "com.github.copilot.agent.input.CopilotAgentInputTextArea"

internal fun classifyChatSurface(
    toolWindowId: String,
    stripeTitle: String,
    hasAiAssistantClass: Boolean,
): ChatSurface? {
    if (hasAiAssistantClass || "$toolWindowId $stripeTitle".contains("AI Assistant", ignoreCase = true)) {
        return ChatSurface.AI_ASSISTANT
    }
    if (toolWindowId == COPILOT_CHAT_TOOL_WINDOW_ID ||
        "$toolWindowId $stripeTitle".contains("GitHub Copilot Chat", ignoreCase = true)
    ) {
        return ChatSurface.COPILOT
    }
    if (toolWindowId == GEMINI_CHAT_TOOL_WINDOW_ID) {
        return ChatSurface.GEMINI
    }
    return null
}

internal fun shouldUseGenericTextFallback(surface: ChatSurface?): Boolean = surface == null
