package com.wichat.media

/** File names arrive from other people, so they must never be able to point outside our folder. */
object FileNames {
    private const val MAX_LENGTH = 120

    fun safe(raw: String?, fallback: String = "file"): String {
        val base = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filter { it >= ' ' && it !in "<>:\"|?*" }
            .trim()
            .trimStart('.')
        if (base.isEmpty()) return fallback
        if (base.length <= MAX_LENGTH) return base
        val ext = base.substringAfterLast('.', "").take(10)
        return if (ext.isEmpty()) base.take(MAX_LENGTH) else base.take(MAX_LENGTH - ext.length - 1) + "." + ext
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    fun duration(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
