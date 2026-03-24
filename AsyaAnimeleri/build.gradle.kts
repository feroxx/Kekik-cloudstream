version = 1

cloudstream {
    authors     = listOf("patr0n")
    language    = "tr"
    description = "Asya Animeleri - Anime izle, Donghua izle, Türkçe Altyazılı Anime izle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=asyaanimeleri.top&sz=%size%"
}
