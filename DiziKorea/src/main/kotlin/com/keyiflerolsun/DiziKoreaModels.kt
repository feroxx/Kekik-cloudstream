// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty

data class KoreaSearchItem(
    @JsonProperty("title") val title: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("type") val type: String?
)

data class KoreaSearchResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("items") val items: List<KoreaSearchItem>?
)