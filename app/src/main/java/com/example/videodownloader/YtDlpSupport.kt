package com.example.videodownloader

import android.content.Context
import androidx.core.net.toUri
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.collections.set

internal fun isYouTubeUrl(url: String): Boolean {
    val host = url.toUri()
        .host
        ?.lowercase(Locale.ROOT)
        .orEmpty()

    return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be"
}

internal fun quickJsExecutablePath(context: Context): String {
    val executable = File(
        context.applicationInfo.nativeLibraryDir,
        "libqjs.so"
    )

    if (!executable.isFile || !executable.canExecute()) {
        throw IOException(
            "QuickJS is missing or not executable: ${executable.absolutePath}"
        )
    }

    return executable.absolutePath
}

internal fun createQuickJsRuntimeOptions(
    python: Python,
    context: Context
): PyObject {
    val runtimeOptions = python.builtins.callAttr("dict")

    runtimeOptions.asMap()[PyObject.fromJava("path")] =
        PyObject.fromJava(quickJsExecutablePath(context))

    val runtimes = python.builtins.callAttr("dict")

    runtimes.asMap()[PyObject.fromJava("quickjs")] =
        runtimeOptions

    return runtimes
}

internal fun createYouTubeExtractorArgs(python: Python): PyObject {
    val playerClients = python.builtins.callAttr("list")
    playerClients.callAttr("append", PyObject.fromJava("default"))
    playerClients.callAttr("append", PyObject.fromJava("-visionos"))

    val youtubeArgs = python.builtins.callAttr("dict")
    youtubeArgs.asMap()[PyObject.fromJava("player_client")] = playerClients

    val extractorArgs = python.builtins.callAttr("dict")
    extractorArgs.asMap()[PyObject.fromJava("youtube")] = youtubeArgs

    return extractorArgs
}