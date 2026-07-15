package com.harish.heymate.wifitransfer

/** Kind of media stored on the glasses. Derived from a file's extension. */
enum class MediaKind { PHOTO, VIDEO, AUDIO, OTHER }

/**
 * One media item on the glasses, as listed in the device's manifest (`vf_list.txt`).
 *
 * [name] is the bare filename (e.g. `video-2025112233.mp4`); [sizeBytes] is its size if the
 * manifest provides it, or null when unknown. [remotePath] is the absolute path on the device
 * used to build its download URL.
 */
data class MediaFile(
    val name: String,
    val sizeBytes: Long?,
    val remotePath: String,
) {
    val kind: MediaKind = kindOf(name)

    /** Human-readable size, e.g. "4.2 MB", or "—" when unknown. */
    val sizeLabel: String get() = sizeBytes?.let(::formatBytes) ?: "—"

    companion object {
        private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "heic", "webp")
        private val VIDEO_EXT = setOf("mp4", "mov", "avi", "mkv")
        private val AUDIO_EXT = setOf("wav", "mp3", "amr", "aac", "m4a", "opus")

        private fun kindOf(name: String): MediaKind =
            when (name.substringAfterLast('.', "").lowercase()) {
                in PHOTO_EXT -> MediaKind.PHOTO
                in VIDEO_EXT -> MediaKind.VIDEO
                in AUDIO_EXT -> MediaKind.AUDIO
                else -> MediaKind.OTHER
            }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            return "%.1f %s".format(value, units[unit])
        }
    }
}
