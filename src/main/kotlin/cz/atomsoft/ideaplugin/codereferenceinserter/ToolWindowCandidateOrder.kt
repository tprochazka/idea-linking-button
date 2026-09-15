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

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Orders tool windows by how likely they are to be the user's intended send target:
 * active, then the action's recent-focus stack, then other visible windows, with
 * [deprioritizedIds] considered last.
 */
internal object ToolWindowCandidateOrder {

    fun current(
        toolWindowManager: ToolWindowManager,
        deprioritizedIds: Set<String>,
        recentlyActiveToolWindowIds: List<String>,
    ): List<ToolWindow> {
        val toolWindows = toolWindowManager.toolWindowIds.associateWith(toolWindowManager::getToolWindow)
        return orderedIds(
            activeToolWindowId = toolWindowManager.activeToolWindowId,
            lastActiveToolWindowId = toolWindowManager.lastActiveToolWindowId,
            recentlyActiveToolWindowIds = recentlyActiveToolWindowIds,
            toolWindowIds = toolWindowManager.toolWindowIds.asList(),
            isVisible = { id -> toolWindows[id]?.isVisible == true },
            deprioritizedIds = deprioritizedIds,
        ).mapNotNull(toolWindows::get)
    }

    fun describe(toolWindows: List<ToolWindow>): String =
        toolWindows.joinToString(prefix = "[", postfix = "]") { toolWindow ->
            val contentName = toolWindow.contentManager.selectedContent?.displayName?.ifBlank { "unnamed" } ?: "none"
            "${toolWindow.id}:$contentName"
        }
}

/**
 * Returns target IDs in focus order. [recentlyActiveToolWindowIds] comes from the public
 * PlatformDataKeys.LAST_ACTIVE_TOOL_WINDOWS action-data key and preserves meaningful focus when
 * the action's Project View context becomes active.
 */
internal fun orderedIds(
    activeToolWindowId: String?,
    lastActiveToolWindowId: String?,
    recentlyActiveToolWindowIds: List<String>,
    toolWindowIds: List<String>,
    isVisible: (String) -> Boolean,
    deprioritizedIds: Set<String>,
): List<String> {
    val ids = LinkedHashSet<String>()
    activeToolWindowId?.takeIf(isVisible)?.let(ids::add)
    recentlyActiveToolWindowIds.filter(isVisible).forEach(ids::add)
    lastActiveToolWindowId?.takeIf(isVisible)?.let(ids::add)
    toolWindowIds.filter { it !in deprioritizedIds && isVisible(it) }.forEach(ids::add)
    toolWindowIds.filter { it in deprioritizedIds && isVisible(it) }.forEach(ids::add)
    return ids.toList()
}
