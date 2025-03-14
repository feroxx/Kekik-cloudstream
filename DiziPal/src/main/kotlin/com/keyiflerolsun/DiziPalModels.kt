// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty


data class SearchItem(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("tr_title") val trTitle: String,
    @JsonProperty("poster") val poster: String,
    @JsonProperty("genres") val genres: String,
    @JsonProperty("imdb") val imdb: String,
    @JsonProperty("duration") val duration: String,
    @JsonProperty("year") val year: String,
    @JsonProperty("view") val view: Int,
    @JsonProperty("type") val type: String = "defaultType",
    @JsonProperty("url") val url: String
)