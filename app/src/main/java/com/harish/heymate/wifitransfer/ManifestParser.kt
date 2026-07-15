package com.harish.heymate.wifitransfer

/**
 * Parses the glasses' media manifest (`vf_list.txt`) into [MediaFile]s.
 *
 * The exact on-wire format is confirmed on the first real transfer; until then this parser is
 * deliberately tolerant, accepting the layouts an embedded device is likely to emit:
 *
 *   - one filename per line:                 `video-2025112233.mp4`
 *   - filename and size, various delimiters: `video-2025112233.mp4,1048576`
 *                                            `video-2025112233.mp4|1048576`
 *                                            `video-2025112233.mp4 1048576`
 *                                            `video-2025112233.mp4\t1048576`
 *
 * Blank lines, comment lines (`#`), and any leading directory prefix on the name are ignored. A
 * line with no recognizable filename is skipped rather than failing the whole parse.
 */
object ManifestParser {

    private val DELIMITERS = charArrayOf(',', '|', ';', '\t', ' ')

    /**
     * @param text the manifest file contents.
     * @param remoteDir absolute device directory the files live in, used to build each
     *        [MediaFile.remotePath] (e.g. `/storage/sd0/C/DCIM/1/`). Trailing slash optional.
     */
    fun parse(text: String, remoteDir: String): List<MediaFile> {
        val dir = if (remoteDir.endsWith('/')) remoteDir else "$remoteDir/"
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { parseLine(it, dir) }
            .toList()
    }

    private fun parseLine(line: String, dir: String): MediaFile? {
        val (rawName, size) = splitNameAndSize(line)
        // Drop any directory prefix the device may include; we only want the bare filename.
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty() || !name.contains('.')) return null
        return MediaFile(name = name, sizeBytes = size, remotePath = "$dir$name")
    }

    /** Split a line into (name, size?) at the first delimiter that yields a numeric size. */
    private fun splitNameAndSize(line: String): Pair<String, Long?> {
        for (delim in DELIMITERS) {
            val idx = line.indexOf(delim)
            if (idx <= 0) continue
            val maybeName = line.substring(0, idx).trim()
            val maybeSize = line.substring(idx + 1).trim().toLongOrNull()
            if (maybeSize != null) return maybeName to maybeSize
        }
        return line to null
    }
}
