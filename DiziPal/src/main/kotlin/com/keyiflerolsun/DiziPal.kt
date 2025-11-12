package com.keyiflerolsun

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1217.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // Cloudflare bypass
    override var sequentialMainPage           = true
    override var sequentialMainPageDelay      = 50L
    override var sequentialMainPageScrollDelay= 50L

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
        "${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama="  to "Anime",
        "${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="   to "Bilimkurgu Dizileri",
        "${mainUrl}/tur/bilimkurgu"                                to "Bilimkurgu Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama="  to "Komedi Dizileri",
        "${mainUrl}/tur/komedi"                                    to "Komedi Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="   to "Belgesel Dizileri",
        "${mainUrl}/tur/belgesel"                                  to "Belgesel Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=25&type=&siralama="  to "Erotik Diziler",
        "${mainUrl}/tur/erotik"                                    to "Erotik Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSeries = request.data.contains("/diziler")
        val apiUrl = if (isSeries) {
            "$mainUrl/api/load-series"
        } else {
            "$mainUrl/api/load-movies"
        }

        val response = app.post(
            apiUrl,
            data = mapOf("page" to page.toString()),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to request.data
            ),
            interceptor = interceptor
        )
        val doc = Jsoup.parse(response.text)
        val items = if (isSeries) {
            doc.select("li").mapNotNull { it.parseSeriesItem() }
        } else {
            doc.select("li").mapNotNull { it.parseMovieItem() }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.parseSeriesItem(): SearchResponse? {
        val title     = this.selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.parseMovieItem(): SearchResponse? {
        val title     = this.selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseRaw = app.post(
            "${mainUrl}/api/search-autocomplete",
            headers = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "${mainUrl}/",
            data = mapOf("query" to query),
            interceptor = interceptor
        )

        val searchItemsMap: Map<String, SearchItem> = jacksonObjectMapper().readValue(responseRaw.text)
        return searchItemsMap.values.map { it.toPostSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = getHeaders(mainUrl)).document

        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val year        = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div").text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags        = document.selectXpath("//div[text()='Türler']//following-sibling::div").text().trim().split(" ").map { it.trim() }
        val rating      = document.selectXpath("//div[text()='IMDB Puanı']//following-sibling::div").text().trim().toRatingInt()
        val duration    = Regex("(\\d+)").find(document.selectXpath("//div[text()='Ortalama Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        return if (url.contains("/dizi/")) {
            val title    = document.selectFirst("div.cover h5")?.text() ?: return null
            val episodes = document.select("div.episode-item").mapNotNull {
                val epName    = it.selectFirst("div.name")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epInfo    = it.selectFirst("div.episode")?.text()?.trim()?.split(" ") ?: listOf()
                val epSeason  = epInfo.getOrNull(0)?.replace(".", "")?.toIntOrNull()
                val epEpisode = epInfo.getOrNull(2)?.replace(".", "")?.toIntOrNull()
                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
            }
        } else {
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "data » $data")
        val document = app.get(data, interceptor = interceptor, headers = getHeaders(mainUrl)).document
        val iframe   = document.selectFirst(".series-player-container iframe")?.attr("src")
                       ?: document.selectFirst("div#vast_new iframe")?.attr("src")
                       ?: return false
        Log.d("DZP", "iframe » $iframe")

        val iSource = app.get(iframe, referer = "${mainUrl}/").text
        val m3uLink = Regex("""file:"([^"]+)""").find(iSource)?.groupValues?.get(1)
        if (m3uLink == null) {
            Log.d("DZP", "iSource » $iSource")
            return loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        val subtitles = Regex(""""subtitle":"([^"]+)""").find(iSource)?.groupValues?.get(1)
        if (subtitles != null) {
            if (subtitles.contains(",")) {
                subtitles.split(",").forEach {
                    val subLang = it.substringAfter("[").substringBefore("]")
                    val subUrl  = it.replace("[${subLang}]", "")
                    subtitleCallback.invoke(SubtitleFile(lang = subLang, url = fixUrl(subUrl)))
                }
            } else {
                val subLang = subtitles.substringAfter("[").substringBefore("]")
                val subUrl  = subtitles.replace("[${subLang}]", "")
                subtitleCallback.invoke(SubtitleFile(lang = subLang, url = fixUrl(subUrl)))
            }
        }

        callback.invoke(
            newExtractorLink(
                source   = this.name,
                name     = this.name,
                url      = m3uLink,
                type     = ExtractorLinkType.M3U8
            ) {
                headers = mapOf("Referer" to "${mainUrl}/")
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to USER_AGENT
        )
    }
}
