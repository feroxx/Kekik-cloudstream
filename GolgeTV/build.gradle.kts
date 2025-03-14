version = 4

cloudstream {
    authors     = listOf("usdogu", "keyiflerolsun")
    language    = "tr"
    description = "GolgeTV cloudstream eklentisidir. Sevdiğiniz yayın platformlarını ve canlı maçları burada bulabilirsiniz."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 3 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://www.apkdelisi.net/wp-content/uploads/2022/03/golge-tv-reklamsiz-mod-apk-canli-tv-apkdelisi-0.jpg"
}