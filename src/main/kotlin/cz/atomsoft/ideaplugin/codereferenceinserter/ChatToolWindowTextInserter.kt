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
import java.util.concurrent.CancellationException
import javax.swing.text.JTextComponent
import javax.swing.SwingUtilities

/**
 * Finds and writes into chat inputs of IntelliJ's built-in AI Assistant/ACP, GitHub Copilot,
 * or Android Studio Gemini tool windows. None of these chat implementations is a compile-time
 * dependency, so detection uses the already-loaded Swing component tree or a narrow reflective
 * bridge, in the same compatibility-first spirit as [TerminalTextInserter].
 */
@Service(Service.Level.PROJECT)
class ChatToolWindowTextInserter(private val project: Project) {

    private val logger = logger<ChatToolWindowTextInserter>()
    var lastLookupSummary: String = "Chat lookup has not run yet."
        private set

    internal fun findTargetInContent(toolWindow: ToolWindow, content: Content): TextInsertTarget? {
        val surface = classifyChatSurface(
            toolWindow.id,
            runCatching { toolWindow.stripeTitle }.getOrDefault(""),
            containsClassPrefix(content.component, AI_ASSISTANT_CLASS_PREFIX),
        )
        if (surface == null) {
            lastLookupSummary = "toolWindow=${toolWindow.id} is not a recognized chat window."
            return null
        }

        val target = when (surface) {
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
        }
        if (target == null) {
            lastLookupSummary = "toolWindow=${toolWindow.id} matched $surface but no writable input was found."
            return null
        }

        lastLookupSummary = "toolWindow=${toolWindow.id} matched $surface chat input."
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
                logger.warn("Copilot chat input insertion was requested off the EDT")
                return false
            }
            component.replaceSelection(text)
            true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to insert text into Copilot chat input", e)
            false
        }
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
