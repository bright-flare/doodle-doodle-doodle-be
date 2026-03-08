package com.gaetteok.backend.infrastructure.keyword

import com.gaetteok.backend.domain.game.port.KeywordCatalog
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class RemoteBackedKeywordCatalog(
    @param:Value("\${gaetteok.game.keyword-source-url:https://cdn.jsdelivr.net/npm/pd-korean-noun-list-for-wordles/src/CommonNouns.js}")
    private val keywordSourceUrl: String,
    @param:Value("\${gaetteok.game.keyword-cache-ttl-ms:21600000}")
    private val keywordCacheTtlMs: Long,
) : KeywordCatalog {
    private val fallbackKeywords = loadFallbackKeywords()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var cachedKeywords: List<String> = fallbackKeywords

    @Volatile
    private var lastLoadedAt: Long = 0

    override fun keywords(): List<String> {
        val currentTime = System.currentTimeMillis()
        if (cachedKeywords.isNotEmpty() && currentTime - lastLoadedAt < keywordCacheTtlMs) {
            return cachedKeywords
        }

        synchronized(this) {
            val refreshedTime = System.currentTimeMillis()
            if (cachedKeywords.isNotEmpty() && refreshedTime - lastLoadedAt < keywordCacheTtlMs) {
                return cachedKeywords
            }

            cachedKeywords = fetchRemoteKeywords().ifEmpty { cachedKeywords.ifEmpty { fallbackKeywords } }
            lastLoadedAt = refreshedTime
            return cachedKeywords
        }
    }

    private fun fetchRemoteKeywords(): List<String> {
        return runCatching {
            val request = HttpRequest.newBuilder(URI.create(keywordSourceUrl))
                .GET()
                .timeout(Duration.ofSeconds(3))
                .header("User-Agent", "gaetteok-backend/0.1")
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return emptyList()
            }
            parseKeywordSource(response.body())
        }.getOrDefault(emptyList())
    }

    private fun parseKeywordSource(raw: String): List<String> {
        val regex = Regex("'([^']+)'")
        return sanitizeKeywords(regex.findAll(raw).map { it.groupValues[1] }.toList())
    }

    private fun sanitizeKeywords(words: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        words.forEach { raw ->
            val keyword = raw.trim().replace(Regex("\\s+"), "")
            if (keyword.length !in 2..7) return@forEach
            if (!keyword.matches(Regex("[0-9A-Za-z가-힣]+"))) return@forEach
            seen += keyword
        }
        return seen.ifEmpty { fallbackKeywords }.toList()
    }

    private fun loadFallbackKeywords(): List<String> {
        val resource = ClassPathResource("keywords/fallback-ko.txt")
        val lines = resource.inputStream.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            sequence.map(String::trim).filter(String::isNotBlank).toList()
        }
        return sanitizeKeywords(lines)
    }
}
