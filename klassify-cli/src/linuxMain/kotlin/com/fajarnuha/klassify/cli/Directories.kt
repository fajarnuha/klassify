package com.fajarnuha.klassify.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.mkdir

@OptIn(ExperimentalForeignApi::class)
internal actual fun makeDirectory(path: String): Int = mkdir(path, 448.convert())
