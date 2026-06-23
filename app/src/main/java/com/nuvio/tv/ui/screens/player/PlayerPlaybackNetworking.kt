package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Bytes fetched during a prewarm to prime DNS/TLS + the debrid CDN edge. */
    private const val DEFAULT_WARM_BYTES = 1L * 1024 * 1024 // 1 MB
    private const val DEFAULT_WARM_TIMEOUT_MS = 8_000L

    /**
     * Prewarm (Fase B): open a real connection to the resolved playback URL and pull
     * a small ranged chunk, then discard it. This primes the shared playback OkHttp
     * connection pool (DNS + TLS handshake) and forces the debrid provider to pull the
     * file to its CDN edge, so the actual player reaches first frame faster.
     *
     * Deliberately does NOT touch ExoPlayer/Media3: no decoders, no surfaces, no
     * codec/DV/HDR/MPV state — so it is regression-free and safe on low-RAM TV sticks.
     * Cancellable (e.g. when leaving the detail screen) and bounded by [maxBytes] and
     * [timeoutMs]. Reuses the same [playbackHttpClient] the player's data source draws
     * from, so the warmed connection is reused rather than re-established.
     */
    suspend fun warmConnection(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = DEFAULT_WARM_BYTES,
        timeoutMs: Long = DEFAULT_WARM_TIMEOUT_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return@withContext false

        val builder = playbackHttpClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        val authValue = headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
        if (authValue != null) {
            // Mirror createHttpDataSourceFactory: keep Authorization across cross-host redirects.
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(request.newBuilder().header("Authorization", authValue).build())
                } else {
                    chain.proceed(request)
                }
            }
        }
        val client = builder.build()

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${maxBytes - 1}")
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }
        val call = client.newCall(requestBuilder.build())
        try {
            runInterruptible {
                call.execute().use { response ->
                    val stream = response.body?.byteStream() ?: return@use
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (read < maxBytes) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        read += n
                    }
                }
            }
            true
        } catch (t: Throwable) {
            call.cancel()
            if (t is kotlinx.coroutines.CancellationException) throw t
            false
        }
    }

    @OptIn(UnstableApi::class)
    fun createHttpDataSourceFactory(defaultHeaders: Map<String, String> = emptyMap()): DataSource.Factory {
        val builder = playbackHttpClient.newBuilder()
        if (defaultHeaders.any { it.key.equals("Authorization", ignoreCase = true) }) {
            // OkHttp strips the Authorization header on cross-host redirects.
            // WebDAV servers behind reverse proxies commonly redirect to a
            // different host/port, causing auth to be lost. A network
            // interceptor ensures the header is always present on every
            // outgoing request — same behavior as mpv/curl.
            val authValue = defaultHeaders.entries
                .first { it.key.equals("Authorization", ignoreCase = true) }
                .value
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", authValue)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
        }
        val client = builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders))
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = playbackHostnameVerifier
            }
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}