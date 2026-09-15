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

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.content.Content
import java.awt.Component
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

/**
 * Reflective bridge to Android Studio Gemini's Compose query box.
 *
 * Gemini is bundled by Android Studio rather than exposed as a stable IntelliJ Platform
 * dependency. Its tool-window content keeps the [com.google.studiobot.controller.TrajectoryPanelController]
 * as the content disposer; that controller owns the timeline controller and its query-box
 * controller. We use only those narrow, discovered boundaries and fail closed when a future
 * Gemini build changes them.
 */
internal object GeminiToolWindowBridge {

    private const val PANEL_CONTROLLER_CLASS_PREFIX =
        "com.google.studiobot.controller.TrajectoryPanelController"
    private const val TIMELINE_CONTROLLER_GETTER = "getTimelineController"
    private const val QUERY_BOX_CONTROLLER_FIELD = "queryBoxController"
    private const val QUERY_BOX_ACCESSOR_PREFIX = "access\$getQueryBoxController\$p"
    private const val QUERY_BOX_INSERT_METHOD = "insertQuery"
    private const val QUERY_BOX_FOCUS_METHOD = "requestFocusInQueryBox"

    fun findTarget(content: Content): GeminiQueryBoxTarget? {
        val panelController = content.disposer ?: return null
        if (!panelController.javaClass.name.startsWith(PANEL_CONTROLLER_CLASS_PREFIX)) {
            return null
        }

        return try {
            val timelineController = invokeNoArg(panelController, TIMELINE_CONTROLLER_GETTER)
                ?: return null
            val queryBoxController = readQueryBoxController(timelineController)
                ?: return null
            if (findInsertMethod(queryBoxController) == null) return null

            GeminiQueryBoxTarget(
                queryBoxController = queryBoxController,
                timelineController = timelineController,
                fallbackFocusComponent = content.component,
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun readQueryBoxController(timelineController: Any): Any? {
        findField(timelineController.javaClass, QUERY_BOX_CONTROLLER_FIELD)?.let { field ->
            if (field.trySetAccessible()) {
                return field.get(timelineController)
            }
        }

        // Kotlin emits this public synthetic accessor for the controller's nested lambdas.
        val accessor = timelineController.javaClass.methods.firstOrNull { method ->
            method.name.startsWith(QUERY_BOX_ACCESSOR_PREFIX) &&
                method.parameterCount == 1 &&
                java.lang.reflect.Modifier.isStatic(method.modifiers)
        }
        return accessor?.invoke(null, timelineController)
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? =
        target.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterCount == 0
        }?.invoke(target)

    private fun findInsertMethod(controller: Any): Method? =
        controller.javaClass.methods.firstOrNull { method ->
            method.name == QUERY_BOX_INSERT_METHOD &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        }

    internal fun insert(target: GeminiQueryBoxTarget, text: String): Boolean {
        if (!SwingUtilities.isEventDispatchThread()) return false
        return try {
            val insertMethod = findInsertMethod(target.queryBoxController) ?: return false
            insertMethod.invoke(target.queryBoxController, text)
            true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvocationTargetException) {
            throwTargetException(e)
        } catch (_: Throwable) {
            false
        }
    }

    internal fun focus(target: GeminiQueryBoxTarget) {
        try {
            val focusMethod = target.timelineController.javaClass.declaredMethods.firstOrNull { method ->
                method.name == QUERY_BOX_FOCUS_METHOD && method.parameterCount == 0
            }
            if (focusMethod != null && focusMethod.trySetAccessible()) {
                focusMethod.invoke(target.timelineController)
                return
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvocationTargetException) {
            rethrowCancellation(e)
        } catch (_: Throwable) {
            // The Compose panel is still a valid best-effort focus target.
        }
        target.fallbackFocusComponent.requestFocusInWindow()
    }

    private fun rethrowCancellation(exception: InvocationTargetException) {
        when (val cause = exception.targetException ?: exception.cause) {
            is ProcessCanceledException -> throw cause
            is CancellationException -> throw cause
        }
    }

    private fun throwTargetException(exception: InvocationTargetException): Nothing {
        when (val cause = exception.targetException ?: exception.cause) {
            is ProcessCanceledException -> throw cause
            is CancellationException -> throw cause
            null -> throw exception
            else -> throw cause
        }
    }
}

internal data class GeminiQueryBoxTarget(
    val queryBoxController: Any,
    val timelineController: Any,
    val fallbackFocusComponent: Component,
)
