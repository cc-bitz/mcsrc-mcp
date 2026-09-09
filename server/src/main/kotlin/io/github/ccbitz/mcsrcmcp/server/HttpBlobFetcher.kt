package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Real network [BlobFetcher] — the only place in this project that talks to the internet. */
class HttpBlobFetcher(
    private val client: HttpClient = HttpClient.newHttpClient(),
) : BlobFetcher {
    override suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) {
        val response = client.send(get(url).build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} fetching $url" }
        response.body()
    }

    override suspend fun fetchIfNoneMatch(url: String, etag: String?): ConditionalFetch = withContext(Dispatchers.IO) {
        val request = get(url).apply { if (etag != null) header("If-None-Match", etag) }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        when {
            response.statusCode() == 304 -> ConditionalFetch.NotModified
            response.statusCode() in 200..299 ->
                ConditionalFetch.Body(response.body(), response.headers().firstValue("ETag").orElse(null))
            else -> error("HTTP ${response.statusCode()} fetching $url")
        }
    }

    private fun get(url: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(url)).GET()
}
