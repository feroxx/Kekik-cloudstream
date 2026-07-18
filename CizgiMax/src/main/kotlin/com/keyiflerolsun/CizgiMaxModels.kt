package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty

data class SearchResult(
    @JsonProperty("animes") val animes: List<SearchAnime>? = null
)

data class SearchAnime(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("poster") val poster: String?
)

data class ServerItem(
    @JsonProperty("type") val type: String?,
    @JsonProperty("resolveUrl") val resolveUrl: String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("src") val src: String?
)

data class ResolveResponse(
    @JsonProperty("id") val id: String?
)

data class TauResponse(
    @JsonProperty("urls") val urls: List<TauUrl>? = null
)

data class TauUrl(
    @JsonProperty("label") val label: String?,
    @JsonProperty("url") val url: String
)