version = 31

cloudstream {
    authors     = listOf("atillagentr")
    language    = "tr"
    description = "dizi ve filmler burada"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://dizipal952.com&sz=%size%"
}
