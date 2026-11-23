package com.techoship.webviewbridge

/** Turns whatever name a page suggests into a safe file name. Pure, so it is unit-tested. */
internal object FileNames {
    const val MAX_LENGTH = 120
    const val FALLBACK = "download"

    private val reserved = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val whitespace = Regex("\\s+")

    /**
     * Keeps only the last path segment, replaces characters that are unsafe in file names,
     * adds an extension from [mimeType] when the name has none, and caps the length.
     */
    fun sanitize(raw: String?, mimeType: String?, extensionFor: (String) -> String?): String {
        var name = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        name = name.replace(reserved, "_").replace(whitespace, " ").trim().trim('.', ' ')
        if (name.isEmpty() || name.all { it == '_' }) name = FALLBACK
        if (extensionOf(name) == null && !mimeType.isNullOrBlank()) {
            val extension = extensionFor(mimeType.substringBefore(';').trim().lowercase())
            if (!extension.isNullOrBlank()) name = "$name.$extension"
        }
        return limit(name)
    }

    /** The extension without the dot, or null. A leading dot alone is not an extension. */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        return if (dot in 1 until name.length - 1) name.substring(dot + 1) else null
    }

    /** Returns [name], or "name (1).ext", "name (2).ext"… for the first one [exists] rejects. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val extension = extensionOf(name)
        val base = if (extension != null) name.dropLast(extension.length + 1) else name
        val suffix = extension?.let { ".$it" }.orEmpty()
        for (index in 1..9_999) {
            val candidate = "$base ($index)$suffix"
            if (!exists(candidate)) return candidate
        }
        return "$base ${System.currentTimeMillis()}$suffix"
    }

    private fun limit(name: String): String {
        if (name.length <= MAX_LENGTH) return name
        val extension = extensionOf(name)
        return if (extension != null && extension.length <= 16) {
            name.take(MAX_LENGTH - extension.length - 1).trimEnd() + "." + extension
        } else {
            name.take(MAX_LENGTH)
        }
    }
}
