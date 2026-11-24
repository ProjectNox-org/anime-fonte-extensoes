package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object AnimePlayerLog {

    const val TAG = "AnimePlayer"

    enum class Category {
        REQUEST_FAILURE,
        AUTH_VERIFY_FAILURE,
        CLOUDFLARE_CHALLENGE_PENDING,
        EMPTY_RESPONSE,
        PARSING_ERROR,
        EXTRACTION_FAILURE,
        VIDEO_CONVERSION_FAILURE,
    }

    @Volatile
    var sink: ((level: String, message: String) -> Unit)? = null

    private val CDN_CGI_PATH_REGEX = Regex("^/cdn-cgi/.*")
    private val KYOZ_MEDIA_ID_REGEX = Regex("(/media/)[^/]+(\\.[A-Za-z0-9]+)$")
    private val HEX_SEGMENT_REGEX = Regex("(/)[0-9a-fA-F]{20,}(?=/|$)")
    private val OPAQUE_SEGMENT_REGEX = Regex("(/)[A-Za-z0-9_]{32,}(?=/|$)")
    private val SENSITIVE_PARAM_REGEX = Regex(
        """(?i)\b(token|ticket|cookie|cf_clearance|authorization|turnstile_token|player_key|key|sig)=([^&\s;"']+)""",
    )
    private val SENSITIVE_TOKEN_REGEX = Regex("[A-Za-z0-9._-]{24,}")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun sanitizeUrlForLog(rawUrl: String): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return sanitizeMessage(rawUrl)
        val path = parsed.encodedPath
            .let { CDN_CGI_PATH_REGEX.replace(it, "/cdn-cgi/<redacted>") }
            .let { KYOZ_MEDIA_ID_REGEX.replace(it, "$1<redacted>$2") }
            .let { HEX_SEGMENT_REGEX.replace(it, "$1<redacted>") }
            .let { OPAQUE_SEGMENT_REGEX.replace(it, "$1<redacted>") }
        val querySuffix = if (parsed.encodedQuery != null) "?<redacted>" else ""
        return "${parsed.scheme}://${parsed.host}$path$querySuffix"
    }

    fun sanitizeMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(SENSITIVE_PARAM_REGEX, "$1=<redacted>")
            .replace(SENSITIVE_TOKEN_REGEX, "<redacted>")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(160)
    }

    fun d(message: String) {
        emit("D", sanitizeLine(message))
    }

    fun w(category: Category, detail: String) {
        // Emit failure diagnostics at ERROR level so host crash-log exports (*:E) capture them.
        emit("E", "[${category.name}] ${sanitizeLine(detail)}")
    }

    fun e(category: Category, detail: String, throwable: Throwable? = null) {
        val errorSuffix = throwable?.let {
            val cleanMsg = sanitizeMessage(it.message)
            if (cleanMsg.isNotEmpty()) {
                " (${it.javaClass.simpleName}: $cleanMsg)"
            } else {
                " (${it.javaClass.simpleName})"
            }
        }.orEmpty()
        emit("E", "[${category.name}] ${sanitizeLine(detail)}$errorSuffix")
    }

    private fun sanitizeLine(raw: String): String = raw
        .replace(SENSITIVE_PARAM_REGEX, "$1=<redacted>")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
        .take(512)

    private fun emit(level: String, message: String) {
        sink?.invoke(level, message)
        runCatching {
            when (level) {
                "D" -> Log.d(TAG, message)
                "W" -> Log.w(TAG, message)
                else -> Log.e(TAG, message)
            }
        }
    }
}
