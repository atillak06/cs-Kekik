// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1217.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/son-bolumler"                          to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/koleksiyon/netflix"                            to "Netflix",
        "${mainUrl}/koleksiyon/exxen"                              to "Exxen",
        "${mainUrl}/koleksiyon/blutv"                              to "BluTV",
        "${mainUrl}/koleksiyon/disney"                             to "Disney+",
        "${mainUrl}/koleksiyon/amazon-prime"                       to "Amazon Prime",
        "${mainUrl}/koleksiyon/tod-bein"                           to "TOD (beIN)",
        "${mainUrl}/koleksiyon/gain"                               to "Gain",
        "${mainUrl}/tur/mubi"                                      to "Mubi",
        "${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        "${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="  to "Bilimkurgu Dizileri",
        "${mainUrl}/tur/bilimkurgu"                                to "Bilimkurgu Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        "${mainUrl}/tur/komedi"                                    to "Komedi Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel Dizileri",
        "${mainUrl}/tur/belgesel"                                  to "Belgesel Filmleri",
    )

    // 🔥 Scroll destekli versiyon
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeList = mutableListOf<SearchResponse>()

        // Diziler mi filmler mi kontrol et
        val isSeries = request.data.contains("/dizi") || request.data.contains("/diziler")
        val isMovies = request.data.contains("/film") || request.data.contains("/filmler")

        if (page == 1) {
            // 🟢 İlk sayfa (normal HTML yükleme)
            val document = app.get(
                request.data, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
            ).document
            Log.d("DZP", "Ana sayfa HTML içeriği:\n${document.outerHtml()}")

            val firstItems = if (request.data.contains("/diziler/son-bolumler")) {
                document.select("div.episode-item").mapNotNull { it.sonBolumler() }
            } else {
                document.select("article.type2 ul li").mapNotNull { it.diziler() }
            }

            homeList.addAll(firstItems)
        } else {
            // 🟣 Scroll ile yüklenen sayfalar
            val endpoint = when {
                isSeries -> "${mainUrl}/api/load-series"
                isMovies -> "${mainUrl}/api/load-movies"
                else -> return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }

            val response = app.post(
                endpoint,
                data = mapOf("page" to page.toString()),
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )

            val json = jacksonObjectMapper().readTree(response.text)
            val html = json["html"]?.asText() ?: ""
            val document = Jsoup.parse(html)
            val moreItems = document.select("article.type2 ul li").mapNotNull { it.diziler() }

            homeList.addAll(moreItems)
        }

        val hasNext = homeList.isNotEmpty()
        return newHomePageResponse(request.name, homeList, hasNext)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("div.name")?.text() ?: return null
        val episode   = this.selectFirst("div.episode")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "$name $episode"

        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = "${mainUrl}${this.url}"
        val posterUrl = this.poster
        return if (this.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseRaw = app.post(
            "${mainUrl}/api/search-autocomplete",
            headers     = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer     = "${mainUrl}/",
            data        = mapOf("query" to query)
        )

        val searchItemsMap = jacksonObjectMapper().readValue<Map<String, SearchItem>>(responseRaw.text)
        val searchResponses = mutableListOf<SearchResponse>()
        for ((_, searchItem) in searchItemsMap) {
            searchResponses.add(searchItem.toPostSearchResult())
        }
        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // (Load ve loadLinks kısımları aynı şekilde bırakıldı, değişiklik gerekmedi)
    // ...
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
    }
}
