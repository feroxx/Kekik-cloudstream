version = 5

cloudstream {
    authors     = listOf("patr0n")
    language    = "tr"
    description = "SelcukFlix eklentisi."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://selcukflix.co/assets/favicon/favicon-32x32.png"
}
