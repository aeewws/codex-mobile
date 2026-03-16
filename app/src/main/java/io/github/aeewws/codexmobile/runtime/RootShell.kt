package io.github.aeewws.codexmobile.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object RootShell {
    private enum class SuFlavor {
        KERNEL_SU,
        GENERIC,
    }

    private val suBinary by lazy {
        listOf("/product/bin/su", "/system/bin/su", "/system/xbin/su", "su")
            .firstOrNull { candidate -> candidate == "su" || File(candidate).canExecute() }
            ?: "su"
    }

    private val suFlavor by lazy { detectSuFlavor() }

    suspend fun run(
        command: String,
        uid: Int? = null,
        timeoutMillis: Long = 15_000L,
    ): ShellResult = withContext(Dispatchers.IO) {
        coroutineScope {
            var lastResult = ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Unable to execute su",
            )
            for (args in candidateArgs(command = command, uid = uid)) {
                val process = try {
                    ProcessBuilder(args).start()
                } catch (t: Throwable) {
                    lastResult = ShellResult(
                        exitCode = -1,
                        stdout = "",
                        stderr = t.message ?: t.javaClass.simpleName,
                    )
                    continue
                }
                val stdoutDeferred = async { process.inputStream.bufferedReader().use { it.readText() } }
                val stderrDeferred = async { process.errorStream.bufferedReader().use { it.readText() } }

                val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                val result = if (!finished) {
                    process.destroyForcibly()
                    ShellResult(
                        exitCode = -1,
                        stdout = stdoutDeferred.await(),
                        stderr = (stderrDeferred.await() + "\nTimed out after ${timeoutMillis}ms").trim(),
                    )
                } else {
                    ShellResult(
                        exitCode = process.exitValue(),
                        stdout = stdoutDeferred.await(),
                        stderr = stderrDeferred.await(),
                    )
                }
                lastResult = result
                if (!looksLikeUnsupportedArgs(result.stderr)) {
                    return@coroutineScope result
                }
            }
            lastResult
        }
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun candidateArgs(command: String, uid: Int?): List<List<String>> {
        val candidates = mutableListOf<List<String>>()
        when (suFlavor) {
            SuFlavor.KERNEL_SU -> {
                candidates += buildKernelSuArgs(command, uid)
                candidates += buildGenericSuArgs(command, uid)
            }
            SuFlavor.GENERIC -> {
                candidates += buildGenericSuArgs(command, uid)
                candidates += buildKernelSuArgs(command, uid)
            }
        }
        candidates += buildPlainSuArgs(command, uid)
        return candidates.distinct()
    }

    private fun buildKernelSuArgs(command: String, uid: Int?): List<String> =
        buildList {
            add(suBinary)
            add("-M")
            if (uid != null) {
                add(uid.toString())
            }
            add("-c")
            add(command)
        }

    private fun buildGenericSuArgs(command: String, uid: Int?): List<String> =
        buildList {
            add(suBinary)
            add("-t")
            add("0")
            if (uid != null) {
                add(uid.toString())
            }
            add("-c")
            add(command)
        }

    private fun buildPlainSuArgs(command: String, uid: Int?): List<String> =
        buildList {
            add(suBinary)
            if (uid != null) {
                add(uid.toString())
            }
            add("-c")
            add(command)
        }

    private fun looksLikeUnsupportedArgs(stderr: String): Boolean {
        val text = stderr.lowercase(Locale.ROOT)
        return text.contains("unrecognized option") ||
            text.contains("invalid option") ||
            text.contains("usage: su")
    }

    private fun detectSuFlavor(): SuFlavor {
        val result = runBlockingProbe(listOf(suBinary, "-v"))
        val probe = buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) {
                append('\n')
                append(result.stderr)
            }
        }.lowercase(Locale.ROOT)
        return if (probe.contains("kernelsu")) {
            SuFlavor.KERNEL_SU
        } else {
            SuFlavor.GENERIC
        }
    }

    private fun runBlockingProbe(args: List<String>): ShellResult {
        val process = try {
            ProcessBuilder(args).start()
        } catch (t: Throwable) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = t.message ?: t.javaClass.simpleName,
            )
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        return ShellResult(
            exitCode = process.waitFor(),
            stdout = stdout,
            stderr = stderr,
        )
    }
}
