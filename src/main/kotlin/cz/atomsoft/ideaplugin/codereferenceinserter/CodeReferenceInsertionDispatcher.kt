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

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.util.concurrent.CancellationException

/**
 * Single entry point for sending the formatted code reference text to whichever active surface
 * (chat tool window or terminal) is the most likely intended target.
 *
 * Candidate order comes from the action's current focus plus its recent tool-window focus stack.
 * A Terminal candidate is dispatched when reached and exclusively: a failed terminal write falls
 * back to the clipboard rather than trying a chat that happened to be visible.
 */
@Service(Service.Level.PROJECT)
class CodeReferenceInsertionDispatcher(private val project: Project) {

    private val logger = logger<CodeReferenceInsertionDispatcher>()
    private val debugLogging = isDebugLoggingEnabled()
    var lastLookupSummary: String = "Insertion has not run yet."
        private set

    fun insert(text: String, recentlyActiveToolWindowIds: List<String>): Boolean {
        return try {
            val chat = project.service<ChatToolWindowTextInserter>()
            val terminal = project.service<TerminalTextInserter>()

            val toolWindowManager = ToolWindowManager.getInstance(project)
            val candidates = ToolWindowCandidateOrder.current(
                toolWindowManager,
                setOf(TerminalToolWindowFactory.TOOL_WINDOW_ID),
                recentlyActiveToolWindowIds,
            )
            debug {
                "candidate order=${runCatching { ToolWindowCandidateOrder.describe(candidates) }.getOrDefault("<unavailable>")}, " +
                    "recent=${recentlyActiveToolWindowIds.joinToString(prefix = "[", postfix = "]")}"
            }

            return dispatchCandidates(
                candidates = candidates,
                isTerminal = { it.id == TerminalToolWindowFactory.TOOL_WINDOW_ID },
                insertIntoTerminal = {
                    terminal.insertIntoSelectedTerminal(text).also {
                        lastLookupSummary = terminal.lastLookupSummary
                        debug { "terminal result=$it summary=$lastLookupSummary" }
                    }
                },
                insertIntoChat = { toolWindow ->
                    val content = toolWindow.contentManager.selectedContent
                    if (content == null) {
                        debug { "chat candidate=${toolWindow.id} has no selected content" }
                        return@dispatchCandidates false
                    }
                    val target = chat.findTargetInContent(toolWindow, content)
                    if (target == null) {
                        debug { "chat candidate=${toolWindow.id} had no writable target" }
                        return@dispatchCandidates false
                    }
                    debug { "chat candidate=${toolWindow.id} target=${target.description}" }
                    if (!target.write(text)) {
                        debug { "chat candidate=${toolWindow.id} write failed target=${target.description}" }
                        return@dispatchCandidates false
                    }
                    activate(target)
                    lastLookupSummary = "Inserted into ${target.description}."
                    debug { "inserted target=${target.description}" }
                    true
                },
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to dispatch code reference insertion", e)
            lastLookupSummary = "Exception during insertion dispatch: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun activate(target: TextInsertTarget) {
        try {
            target.toolWindow?.activate({ target.focus() }, true) ?: target.focus()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to request focus for insertion target", e)
        }
    }

    private inline fun debug(message: () -> String) {
        if (debugLogging) logger.info(message())
    }
}

/**
 * Attempts destinations in focus order. A Terminal candidate is terminal-only: a failed terminal
 * write must fall back to the clipboard, never to a later chat candidate.
 */
internal fun <T> dispatchCandidates(
    candidates: List<T>,
    isTerminal: (T) -> Boolean,
    insertIntoTerminal: () -> Boolean,
    insertIntoChat: (T) -> Boolean,
): Boolean {
    for (candidate in candidates) {
        if (isTerminal(candidate)) return insertIntoTerminal()
        if (insertIntoChat(candidate)) return true
    }
    return insertIntoTerminal()
}
