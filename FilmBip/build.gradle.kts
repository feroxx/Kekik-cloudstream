version = 14

cloudstream {
    authors     = listOf("keyiflerolsun")
    language    = "tr"
    description = "Binlerce yerli yabancı dizi arşivi, tüm sezonlar, kesintisiz bölümler. Filmbip heryerde seninle!"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://filmbip.com/uploads/favicon/original/favicon.webp"
}
