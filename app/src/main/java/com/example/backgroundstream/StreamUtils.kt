package com.example.backgroundstream

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object StreamUtils {

    private const val TAG = "StreamUtils"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val initialized = AtomicBoolean(false)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    sealed class ExtractResult {
        data class Success(val url: String) : ExtractResult()
        data class Failure(val message: String) : ExtractResult()
    }

    private fun initNewPipe() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(OkHttpDownloader(httpClient), Localization.DEFAULT)
        }
    }

    /**
     * Extracts a playable audio URL.
     * Prefer [videoUrl] when known; match [preferredAudioLanguage] when provided.
     */
    suspend fun getAudioStream(
        query: String,
        videoUrl: String? = null,
        preferredAudioLanguage: String? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        try {
            initNewPipe()

            val targetUrl = videoUrl?.takeIf { it.isNotBlank() }
                ?: searchFirstVideoUrl(query)
                ?: return@withContext ExtractResult.Failure(
                    "No YouTube search results for: ${query.take(80)}"
                )

            Log.i(
                TAG,
                "Extracting streams for $targetUrl (lang hint=${preferredAudioLanguage ?: "none"})"
            )
            val info = StreamInfo.getInfo(ServiceList.YouTube, targetUrl)

            val selected = selectBestAudioStream(info.audioStreams, preferredAudioLanguage)
            val audioUrl = selected?.content?.takeIf { it.isNotBlank() }
                ?: info.videoStreams
                    .asSequence()
                    .mapNotNull { stream -> stream.content?.takeIf { it.isNotBlank() } }
                    .firstOrNull()

            if (audioUrl.isNullOrBlank()) {
                ExtractResult.Failure(
                    "No playable streams (audio=${info.audioStreams.size}, video=${info.videoStreams.size})"
                )
            } else {
                Log.i(
                    TAG,
                    "Selected track locale=${selected?.audioLocale} " +
                        "name=${selected?.audioTrackName} type=${selected?.audioTrackType} " +
                        "bitrate=${selected?.averageBitrate}"
                )
                ExtractResult.Success(audioUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            ExtractResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Prefer streams matching [preferredLanguage]; otherwise prefer ORIGINAL
     * (avoid random dubbed tracks), then highest bitrate.
     */
    internal fun selectBestAudioStream(
        streams: List<AudioStream>,
        preferredLanguage: String?
    ): AudioStream? {
        val playable = streams.filter { !it.content.isNullOrBlank() }
        if (playable.isEmpty()) return null

        val matched = preferredLanguage
            ?.takeIf { it.isNotBlank() }
            ?.let { hint -> playable.filter { streamMatchesLanguage(it, hint) } }
            .orEmpty()

        val pool = when {
            matched.isNotEmpty() -> matched
            else -> {
                // No reliable language from YouTube → stick to original audio,
                // not a random high-bitrate dub.
                val originals = playable.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
                when {
                    originals.isNotEmpty() -> originals
                    else -> playable.filter {
                        it.audioTrackType != AudioTrackType.DUBBED &&
                            it.audioTrackType != AudioTrackType.DESCRIPTIVE
                    }.ifEmpty { playable }
                }
            }
        }

        return pool.maxByOrNull { it.averageBitrate }
    }

    private fun streamMatchesLanguage(stream: AudioStream, hint: String): Boolean {
        val normalizedHint = normalizeLang(hint) ?: return false
        val candidates = listOfNotNull(
            stream.audioLocale?.language,
            stream.audioLocale?.toLanguageTag(),
            stream.audioLocale?.displayLanguage,
            stream.audioLocale?.getDisplayLanguage(Locale.ENGLISH),
            stream.audioTrackName,
            stream.audioTrackId
        ).mapNotNull { normalizeLang(it) }

        return candidates.any { candidate ->
            candidate == normalizedHint ||
                candidate.startsWith("$normalizedHint-") ||
                normalizedHint.startsWith("$candidate-") ||
                candidate.contains(normalizedHint) ||
                normalizedHint.contains(candidate)
        }
    }

    private fun normalizeLang(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim().lowercase(Locale.US)
        // Track ids sometimes look like "hi.3" / "en.4"
        value = value.substringBefore('.')
        value = value.replace('_', '-')
        // Map common display names to ISO codes when possible.
        val byName = Locale.getAvailableLocales()
            .firstOrNull {
                it.displayLanguage.equals(raw.trim(), ignoreCase = true) ||
                    it.getDisplayLanguage(Locale.ENGLISH).equals(raw.trim(), ignoreCase = true)
            }
        if (byName != null && byName.language.isNotBlank()) {
            return byName.language.lowercase(Locale.US)
        }
        return value.takeIf { it.length in 2..24 }
    }

    suspend fun getAudioStreamFromQuery(query: String): String? {
        return when (val result = getAudioStream(query)) {
            is ExtractResult.Success -> result.url
            is ExtractResult.Failure -> null
        }
    }

    private fun searchFirstVideoUrl(query: String): String? {
        val cleaned = query
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { return null }

        val searchExtractor = ServiceList.YouTube.getSearchExtractor(cleaned)
        searchExtractor.fetchPage()
        return searchExtractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .firstOrNull()
            ?.url
    }

    private class OkHttpDownloader(
        private val client: OkHttpClient
    ) : Downloader() {

        @Throws(java.io.IOException::class, ReCaptchaException::class)
        override fun execute(request: Request): Response {
            val httpMethod = request.httpMethod()
            val url = request.url()
            val headers = request.headers()
            val dataToSend = request.dataToSend()
            val requestBody = dataToSend?.toRequestBody(null)

            val requestBuilder = okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)

            headers.forEach { (headerName, headerValueList) ->
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            }
            requestBuilder.header("User-Agent", USER_AGENT)

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 429) {
                    throw ReCaptchaException("reCaptcha Challenge requested", url)
                }

                val responseBodyToReturn = response.body?.string().orEmpty()
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    responseBodyToReturn,
                    response.request.url.toString()
                )
            }
        }
    }
}
