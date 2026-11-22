package com.fajarnuha.klassify.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.memset
import platform.windows.CREATE_UNICODE_ENVIRONMENT
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GENERIC_READ
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.GetModuleFileNameW
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.HANDLEVar
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.STD_ERROR_HANDLE
import platform.windows.SetHandleInformation
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile

@OptIn(ExperimentalForeignApi::class)
internal actual fun executablePath(): String = memScoped {
    val capacity = 32768
    val buffer = allocArray<UShortVar>(capacity)
    val length = GetModuleFileNameW(null, buffer, capacity.toUInt())
    check(length > 0u && length < capacity.toUInt()) { "Could not locate the Klassify executable" }
    buffer.toKStringFromUtf16()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun captureRunner(arguments: List<String>, mergeError: Boolean, input: String?): String = memScoped {
    val security = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
        lpSecurityDescriptor = null
        bInheritHandle = 1
    }
    val inputRead = alloc<HANDLEVar>()
    val inputWrite = alloc<HANDLEVar>()
    inputWrite.value = null
    if (input == null) {
        inputRead.value = CreateFileW(
            "NUL", GENERIC_READ, (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(), security.ptr, OPEN_EXISTING.toUInt(), 0u, null,
        )
        check(inputRead.value != INVALID_HANDLE_VALUE) { "Could not open NUL for the recipe runner" }
    } else {
        check(CreatePipe(inputRead.ptr, inputWrite.ptr, security.ptr, 0u) != 0) { "Could not create recipe input pipe" }
    }
    val read = alloc<HANDLEVar>()
    val write = alloc<HANDLEVar>()
    var pipeCreated = false
    try {
        if (input != null) {
            check(SetHandleInformation(inputWrite.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) != 0) {
                "Could not configure recipe input pipe"
            }
        }
        check(CreatePipe(read.ptr, write.ptr, security.ptr, 0u) != 0) { "Could not create recipe runner pipe" }
        pipeCreated = true
        check(SetHandleInformation(read.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) != 0) { "Could not configure recipe runner pipe" }

        val startup = alloc<STARTUPINFOW>().apply {
            memset(ptr, 0, sizeOf<STARTUPINFOW>().convert())
            cb = sizeOf<STARTUPINFOW>().convert()
            dwFlags = STARTF_USESTDHANDLES.toUInt()
            hStdInput = inputRead.value
            hStdOutput = write.value
            hStdError = if (mergeError) write.value else GetStdHandle(STD_ERROR_HANDLE)
        }
        val process = alloc<PROCESS_INFORMATION>()
        val executable = arguments.first().replace('/', '\\') + ".exe"
        val command = (listOf(executable) + arguments.drop(1)).joinToString(" ", transform = ::quoteArgument)
        val environment = runnerEnvironment()
        val started = CreateProcessW(
            executable, command.wcstr.ptr, null, null, 1, CREATE_UNICODE_ENVIRONMENT.toUInt(), environment.wcstr.ptr,
            null, startup.ptr, process.ptr,
        )
        check(started != 0) { "Could not start the recipe runner (Windows error ${GetLastError()})" }

        CloseHandle(write.value)
        write.value = null
        CloseHandle(process.hThread)
        try {
            if (input != null) {
                CloseHandle(inputRead.value)
                inputRead.value = null
                writeRunnerInput(inputWrite.value, input)
                CloseHandle(inputWrite.value)
                inputWrite.value = null
            }
            val output = readRunnerOutput(read.value)
            val status = alloc<UIntVar>()
            check(WaitForSingleObject(process.hProcess, INFINITE) == WAIT_OBJECT_0 &&
                GetExitCodeProcess(process.hProcess, status.ptr) != 0 && status.value == 0u) {
                "Recipe runner failed"
            }
            output
        } finally {
            CloseHandle(process.hProcess)
        }
    } finally {
        inputRead.value?.let { CloseHandle(it) }
        inputWrite.value?.let { CloseHandle(it) }
        if (pipeCreated) {
            write.value?.let { CloseHandle(it) }
            CloseHandle(read.value)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun runnerEnvironment(): String {
    val block = GetEnvironmentStringsW() ?: error("Could not read the recipe runner environment")
    return try {
        buildString {
            var index = 0
            while (block[index] != 0.toUShort()) {
                val entry = buildString {
                    while (block[index] != 0.toUShort()) append(block[index++].toInt().toChar())
                    index++
                }
                if (!entry.startsWith("TYPESAFE_API_KEY=", ignoreCase = true)) {
                    append(entry)
                    append('\u0000')
                }
            }
            if (isEmpty()) append('\u0000')
        }
    } finally {
        FreeEnvironmentStringsW(block)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeRunnerInput(handle: HANDLE?, input: String) = memScoped {
    val bytes = input.encodeToByteArray()
    val count = alloc<UIntVar>()
    var offset = 0
    bytes.usePinned { pinned ->
        while (offset < bytes.size) {
            val success = WriteFile(handle, pinned.addressOf(offset).reinterpret(), (bytes.size - offset).toUInt(), count.ptr, null)
            check(success != 0 && count.value > 0u) { "Could not send recipe runner input" }
            offset += count.value.toInt()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readRunnerOutput(handle: HANDLE?): String = memScoped {
    val count = alloc<UIntVar>()
    val buffer = ByteArray(8192)
    val chunks = mutableListOf<ByteArray>()
    while (true) {
        val success = buffer.usePinned { ReadFile(handle, it.addressOf(0).reinterpret(), buffer.size.toUInt(), count.ptr, null) }
        if (success == 0) {
            check(GetLastError() == ERROR_BROKEN_PIPE.toUInt()) { "Could not read the recipe runner output" }
            break
        }
        if (count.value == 0u) break
        chunks += buffer.copyOf(count.value.toInt())
    }
    val output = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(output, offset)
        offset += chunk.size
    }
    output.decodeToString()
}

// Windows parses slashes before a quote differently from other slashes.
private fun quoteArgument(value: String): String = buildString {
    append('"')
    var slashes = 0
    for (char in value) {
        if (char == '\\') {
            slashes++
            continue
        }
        append("\\".repeat(if (char == '"') slashes * 2 + 1 else slashes))
        append(char)
        slashes = 0
    }
    append("\\".repeat(slashes * 2))
    append('"')
}
