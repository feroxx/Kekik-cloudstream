version = 5

cloudstream {
    authors     = listOf("keyiflerolsun")
    language    = "tr"
    description = "1080P yabancı film izle, Türkçe dublaj ve Türkçe altyazılı film seçenekleri ile Türkiye'nin en geniş film arşivi 720pizle Full hd film izle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=720pizle.ai&sz=%size%"
}