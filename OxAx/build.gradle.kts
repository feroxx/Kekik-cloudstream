version = 1

cloudstream {
    authors     = listOf("keyiflerolsun")
    language    = "ru"
    description = "(VPN) ОХ-АХ.ТВ - сайт для просмотра взрослых ТВ онлайн без каких либо регистраций. Смотрите бесплатно на мобильных устройствах iPad, iPhone и Android."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW", "Live")
    iconUrl = "https://cdn1.iconfinder.com/data/icons/nsfw-1/64/tv-adult-content-porn-512.png"
}