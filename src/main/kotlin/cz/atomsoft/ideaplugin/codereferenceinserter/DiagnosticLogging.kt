/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Code Reference Inserter.
 *
 * Code Reference Inserter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */

package cz.atomsoft.ideaplugin.codereferenceinserter

internal const val DEBUG_LOGGING_PROPERTY = "code.reference.inserter.debug"

internal fun isDebugLoggingEnabled(): Boolean =
    java.lang.Boolean.getBoolean(DEBUG_LOGGING_PROPERTY)
