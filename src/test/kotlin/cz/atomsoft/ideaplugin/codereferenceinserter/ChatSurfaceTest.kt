/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Code Reference Inserter.
 */

package cz.atomsoft.ideaplugin.codereferenceinserter

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSurfaceTest : BasePlatformTestCase() {

    fun testRecognizesAiAssistantByComponentClass() {
        assertEquals(
            ChatSurface.AI_ASSISTANT,
            classifyChatSurface("AIAssistant", "", hasAiAssistantClass = true),
        )
    }

    fun testRecognizesCopilotByToolWindowId() {
        assertEquals(
            ChatSurface.COPILOT,
            classifyChatSurface(COPILOT_CHAT_TOOL_WINDOW_ID, "Chat", hasAiAssistantClass = false),
        )
    }

    fun testRecognizesAndroidStudioGeminiByToolWindowId() {
        assertEquals(
            ChatSurface.GEMINI,
            classifyChatSurface(GEMINI_CHAT_TOOL_WINDOW_ID, "Gemini", hasAiAssistantClass = false),
        )
    }

    fun testGeminiBridgeInsertsIntoQueryBoxController() {
        val queryBox = FakeQueryBoxController()
        val target = GeminiQueryBoxTarget(
            queryBoxController = queryBox,
            timelineController = Any(),
            fallbackFocusComponent = JPanel(),
        )
        val inserted = GeminiToolWindowBridge.insert(target, "reference")

        assertTrue(inserted)
        assertEquals("reference", queryBox.insertedText)
    }

    fun testGeminiBridgeFallsBackWhenComposerFocusFails() {
        val fallback = RecordingFocusComponent()
        val target = GeminiQueryBoxTarget(
            queryBoxController = FakeQueryBoxController(),
            timelineController = FailingFocusTimelineController(),
            fallbackFocusComponent = fallback,
        )

        GeminiToolWindowBridge.focus(target)

        assertTrue(fallback.focusRequested)
    }

    fun testDoesNotClassifyUnrelatedToolWindowAsChat() {
        assertNull(classifyChatSurface("Project", "Project", hasAiAssistantClass = false))
    }

    private class FakeQueryBoxController {
        var insertedText: String? = null

        fun insertQuery(text: String) {
            insertedText = text
        }
    }

    private class FailingFocusTimelineController {
        @Suppress("unused")
        private fun requestFocusInQueryBox() {
            error("simulated focus failure")
        }
    }

    private class RecordingFocusComponent : JPanel() {
        var focusRequested = false

        override fun requestFocusInWindow(): Boolean {
            focusRequested = true
            return true
        }
    }
}
