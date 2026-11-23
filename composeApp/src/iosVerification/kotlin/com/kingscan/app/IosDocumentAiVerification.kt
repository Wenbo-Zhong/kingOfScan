package com.kingscan.app

import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path.Companion.toPath

fun runIosDocumentAiVerification(root: String, completion: (String) -> Unit) {
    AppGraph.init("$root/repository")
    CoroutineScope(Dispatchers.Default).launch {
        val result = try { verifyDocumentAi(root) } catch (failure: Exception) {
            "FAIL ${failure.stackTraceToString()}".also { FileSystem.SYSTEM.write("$root/result.txt".toPath()) { writeUtf8(it) } }
        }
        withContext(Dispatchers.Main) { completion(result) }
    }
}
