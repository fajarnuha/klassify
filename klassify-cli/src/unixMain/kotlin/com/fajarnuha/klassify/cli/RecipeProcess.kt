package com.fajarnuha.klassify.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.EOF
import platform.posix.close
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fputs
import platform.posix.free
import platform.posix.getenv
import platform.posix.mkstemp
import platform.posix.pclose
import platform.posix.popen
import platform.posix.realpath
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun captureRunner(arguments: List<String>, mergeError: Boolean, input: String?): String {
    if (input == null) return capture(arguments, mergeError, "/dev/null")
    return memScoped {
        val directory = getenv("TMPDIR")?.toKString()?.takeIf(String::isNotBlank) ?: "/tmp"
        val template = "${directory.trimEnd('/')}/klassify-input-XXXXXX".cstr.ptr
        val fd = mkstemp(template)
        check(fd >= 0) { "Could not create temporary recipe runner input" }
        val path = template.toKString()
        try {
            val file = fdopen(fd, "w")
            if (file == null) {
                close(fd)
                error("Could not open recipe runner input")
            }
            val written = fputs(input, file)
            val status = fclose(file)
            check(written != EOF && status == 0) { "Could not write recipe runner input" }
            capture(arguments, mergeError, path)
        } finally {
            unlink(path)
        }
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

@OptIn(ExperimentalForeignApi::class)
private fun capture(arguments: List<String>, mergeError: Boolean, inputPath: String): String {
    val command = arguments.joinToString(" ", transform = ::shellQuote)
    val redirect = if (mergeError) " 2>&1" else ""
    val file = popen("unset TYPESAFE_API_KEY; exec $command <${shellQuote(inputPath)}$redirect", "r")
        ?: error("Could not start the recipe runner")
    var status: Int
    val output = try {
        readAll(file)
    } finally {
        status = pclose(file)
    }
    check(status == 0) { "Recipe runner failed" }
    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun canonicalExecutable(path: String): String {
    val resolved = realpath(path, null) ?: error("Could not resolve the Klassify executable path")
    return try { resolved.toKString() } finally { free(resolved) }
}
