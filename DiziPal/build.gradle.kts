version = 15

cloudstream {
    authors     = listOf("keyiflerolsun", "muratcesmecioglu")
    language    = "tr"
    description = "en yeni dizileri güvenli ve hızlı şekilde sunar."

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
