/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Code Reference Inserter.
 */

package cz.atomsoft.ideaplugin.codereferenceinserter

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class ToolWindowCandidateOrderTest : BasePlatformTestCase() {

    fun testLastActiveTerminalIsDispatchedBeforeVisibleChat() {
        val attempts = mutableListOf<String>()

        val inserted = dispatchCandidates(
            candidates = listOf("Terminal", "AI Assistant"),
            isTerminal = { it == "Terminal" },
            insertIntoTerminal = { attempts += "terminal"; true },
            insertIntoChat = { attempts += "chat:$it"; true },
        )

        assertEquals(true, inserted)
        assertEquals(listOf("terminal"), attempts)
    }

    fun testFailedLastActiveTerminalDoesNotFallThroughToChat() {
        val attempts = mutableListOf<String>()

        val inserted = dispatchCandidates(
            candidates = listOf("Terminal", "AI Assistant"),
            isTerminal = { it == "Terminal" },
            insertIntoTerminal = { attempts += "terminal"; false },
            insertIntoChat = { attempts += "chat:$it"; true },
        )

        assertEquals(false, inserted)
        assertEquals(listOf("terminal"), attempts)
    }

    fun testRecentFocusStackKeepsTerminalAheadOfVisibleChatAfterProjectSelection() {
        assertEquals(
            listOf("Project", "Terminal", "AIAssistant"),
            orderedIds(
                activeToolWindowId = "Project",
                lastActiveToolWindowId = "Project",
                recentlyActiveToolWindowIds = listOf("Project", "Terminal", "AIAssistant"),
                toolWindowIds = listOf("Project", "AIAssistant", "Terminal"),
                isVisible = { true },
                deprioritizedIds = setOf("Terminal"),
            ),
        )
    }

    fun testHiddenHistoricalTargetDoesNotPreemptVisibleTarget() {
        assertEquals(
            listOf("Project", "Terminal"),
            orderedIds(
                activeToolWindowId = "Project",
                lastActiveToolWindowId = "Project",
                recentlyActiveToolWindowIds = listOf("Project", "AIAssistant", "Terminal"),
                toolWindowIds = listOf("Project", "AIAssistant", "Terminal"),
                isVisible = { it != "AIAssistant" },
                deprioritizedIds = setOf("Terminal"),
            ),
        )
    }

    fun testRecentAiFocusStopsBeforeTerminalAfterProjectSelection() {
        val attempts = mutableListOf<String>()

        val inserted = dispatchCandidates(
            candidates = listOf("Project", "AIAssistant", "Terminal"),
            isTerminal = { it == "Terminal" },
            insertIntoTerminal = { attempts += "terminal"; true },
            insertIntoChat = { candidate ->
                attempts += "chat:$candidate"
                candidate == "AIAssistant"
            },
        )

        assertEquals(true, inserted)
        assertEquals(listOf("chat:Project", "chat:AIAssistant"), attempts)
    }
}
