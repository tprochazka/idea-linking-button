/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Code Reference Inserter.
 */

package cz.atomsoft.ideaplugin.codereferenceinserter

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel
import javax.swing.JTextField
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

    fun testGenericFallbackAcceptsOneWritableTextInput() {
        val input = JTextField()

        assertTrue(selectGenericTextInput(listOf(input), focusOwner = null) === input)
    }

    fun testGenericFallbackFindsWritableDescendant() {
        val container = JPanel()
        val input = JTextField()
        container.add(input)

        val inserter = ChatToolWindowTextInserter(project)

        assertTrue(inserter.findGenericTextInput(container, focusOwner = null) === input)
    }

    fun testGenericFallbackIgnoresReadOnlyDescendant() {
        val container = JPanel()
        val readOnly = JTextField().apply { isEditable = false }
        container.add(readOnly)

        val inserter = ChatToolWindowTextInserter(project)

        assertNull(inserter.findGenericTextInput(container, focusOwner = null))
    }

    fun testGenericFallbackUsesFocusedInputWhenSeveralExist() {
        val first = JTextField()
        val second = JTextField()

        assertTrue(selectGenericTextInput(listOf(first, second), focusOwner = second) === second)
    }

    fun testGenericFallbackRejectsAmbiguousInputsWithoutFocus() {
        val first = JTextField()
        val second = JTextField()

        assertNull(selectGenericTextInput(listOf(first, second), focusOwner = null))
    }

    fun testDoesNotClassifyUnrelatedToolWindowAsChat() {
        assertNull(classifyChatSurface("Project", "Project", hasAiAssistantClass = false))
    }

    fun testGenericFallbackIsReservedForUnrecognizedToolWindows() {
        assertTrue(shouldUseGenericTextFallback(null))
        assertTrue(!shouldUseGenericTextFallback(ChatSurface.AI_ASSISTANT))
        assertTrue(!shouldUseGenericTextFallback(ChatSurface.COPILOT))
        assertTrue(!shouldUseGenericTextFallback(ChatSurface.GEMINI))
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
