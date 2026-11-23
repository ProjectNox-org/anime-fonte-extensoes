package eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.lib.unpacker.Unpacker
import okhttp3.Headers

object ScriptExtractor {
    private val SOURCE_URL_REGEX = Regex("""\b(?:file|src)\s*["']?\s*:\s*["']([^"']+)["']""")
    private val SOURCE_LABEL_REGEX = Regex("""\blabel\s*["']?\s*:\s*["']([^"']+)["']""")

    fun videosFromScript(scriptData: String, headers: Headers): List<Video> {
        val script = when {
            "eval(function" in scriptData -> Unpacker.unpack(scriptData)
            else -> scriptData
        }.ifEmpty { null }?.replace("\\", "") ?: return emptyList()

        val sourceBlock = when {
            "sources:" in script -> script.substringAfter("sources:")
            ".src(" in script -> script.substringAfter(".src(").substringBefore(")")
            else -> return emptyList()
        }

        val trimmed = sourceBlock.trimStart()
        val listContent = if (trimmed.startsWith("[")) {
            trimmed.substringAfter("[").substringBefore("]")
        } else {
            trimmed.substringBefore(")")
        }

        return listContent
            .split("{")
            .drop(1)
            .mapNotNull { entry ->
                val url = SOURCE_URL_REGEX.find(entry)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: return@mapNotNull null
                val quality = SOURCE_LABEL_REGEX.find(entry)?.groupValues?.get(1)?.trim()
                    ?.ifEmpty { null }
                    ?: "Animes Digital"
                Video(videoUrl = url, videoTitle = quality, headers = headers)
            }
    }
}
