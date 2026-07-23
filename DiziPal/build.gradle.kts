version = 102

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
    iconUrl = "https://dizipal1563.com/assets/favicon/favicon-32x32.png?v=3"
}
