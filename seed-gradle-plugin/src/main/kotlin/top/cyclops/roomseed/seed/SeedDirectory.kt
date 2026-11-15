package top.cyclops.roomseed.seed

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import top.cyclops.roomseed.seed.format.SeedFormats

internal object SeedDirectory {
    fun discover(
        root: Path,
        orderedPaths: List<String>? = null,
        excludedDirectories: Set<Path> = emptySet(),
    ): List<SeedFile> {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS))
            throw SeedInputException("Invalid seed directory: $root")
        val candidates = Files.walk(root).use { paths ->
            paths.filter { file ->
                file != root && !Files.isDirectory(file, NOFOLLOW_LINKS) &&
                    extension(file) in SeedFormats.extensions &&
                    excludedDirectories.none(file::startsWith)
            }.map { file -> root.relativize(file).joinToString("/") }.sorted().toList()
        }
        if (orderedPaths == null) return candidates.map { readFile(root, root.resolve(it)) }

        val selected = linkedSetOf<String>()
        for (pattern in orderedPaths) {
            val segments = pattern.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." || ("**" in it && it != "**") } ||
                pattern.any { it == '\\' || it == ':' || it.isISOControl() }
            ) {
                throw SeedInputException("$root: invalid relative seed path or pattern '$pattern'")
            }
            val matches = if ('*' in pattern) {
                val glob = globRegex(segments)
                candidates.filter(glob::matches)
            } else {
                listOf(pattern).filter { it in candidates }
            }
            if (matches.isEmpty()) throw SeedInputException("$root: no supported seed files match '$pattern'")
            for (path in matches) {
                if (!selected.add(path)) throw SeedInputException("$root: seed file '$path' is listed more than once")
            }
        }
        return selected.map { readFile(root, root.resolve(it)) }
    }

    private fun globRegex(segments: List<String>): Regex = Regex(
        buildString {
            for ((index, segment) in segments.withIndex()) {
                if (segment == "**") {
                    append(if (index == segments.lastIndex) ".*" else "(?:[^/]+/)*")
                } else {
                    append(segment.split('*').joinToString("[^/]*") { Regex.escape(it) })
                    if (index != segments.lastIndex) append('/')
                }
            }
        },
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun readFile(root: Path, file: Path): SeedFile {
        val format = extension(file)
        if (format !in SeedFormats.extensions)
            throw SeedInputException("Unsupported seed file format: $file")
        val relative = root.relativize(file)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || !Files.isReadable(file) ||
            file.toRealPath() != root.toRealPath().resolve(relative)
        ) {
            throw SeedInputException("Seed input must be a readable regular file inside $root: $file")
        }
        return SeedFile(relative.joinToString("/"), file, format)
    }

    private fun extension(file: Path): String = file.fileName.toString().substringAfterLast('.', "")
}
