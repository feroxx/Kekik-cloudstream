// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecItem(
    @JsonProperty("id")          val id: Int?              = null,
    @JsonProperty("type")        val type: String?         = null,
    @JsonProperty("title")       val title: String?        = null,
    @JsonProperty("label")       val label: String?        = null,
    @JsonProperty("sublabel")    val sublabel: String?     = null,
    @JsonProperty("description") val description: String?  = null,
    @JsonProperty("year")        val year: Int?            = null,
    @JsonProperty("imdb")        val imdb: Int?            = null,
    @JsonProperty("rating")      val rating: Float?        = null,
    @JsonProperty("duration")    val duration: String?     = null,
    @JsonProperty("image")       val image: String?        = null,
    @JsonProperty("genres")      val genres: List<Genre>?  = null,
    @JsonProperty("trailer")     val trailer: Trailer?     = null,
    @JsonProperty("sources")     val sources: List<Source>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("id")    val id: Int?       = null,
    @JsonProperty("title") val title: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Trailer(
    @JsonProperty("id")   val id: Int?       = null,
    @JsonProperty("type") val type: String?  = null,
    @JsonProperty("url")  val url: String?   = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Source(
    @JsonProperty("id")     val id: Int?        = null,
    @JsonProperty("type")   val type: String?   = null,
    @JsonProperty("url")    val url: String?    = null,
    @JsonProperty("locked") val locked: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecSearch(
    @JsonProperty("channels") val channels: List<RecItem>? = emptyList(),
    @JsonProperty("posters")  val posters: List<RecItem>?  = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecDizi(
    @JsonProperty("id")       val id: Int?                  = null,
    @JsonProperty("title")    val title: String?            = null,
    @JsonProperty("episodes") val episodes: List<RecEpisode>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecEpisode(
    @JsonProperty("id")      val id: Int?              = null,
    @JsonProperty("title")   val title: String?        = null,
    @JsonProperty("sources") val sources: List<Source>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecNonce(
    @JsonProperty("nonce") val nonce: String? = null
)
