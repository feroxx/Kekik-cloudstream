// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty

data class RecItem(
    @JsonProperty("id")          val id: Int,
    @JsonProperty("type")        val type: String?,
    @JsonProperty("title")       val title: String,
    @JsonProperty("label")       val label: String?,
    @JsonProperty("sublabel")    val sublabel: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("year")        val year: Int?,
    @JsonProperty("imdb")        val imdb: Int?,
    @JsonProperty("rating")      val rating: Float?,
    @JsonProperty("duration")    val duration: String?,
    @JsonProperty("image")       val image: String,
    @JsonProperty("genres")      val genres: List<Genre>?,
    @JsonProperty("trailer")     val trailer: Trailer?,
    @JsonProperty("sources")     val sources: List<Source>
)

data class Genre(
    @JsonProperty("id")    val id: Int,
    @JsonProperty("title") val title: String
)

data class Trailer(
    @JsonProperty("id")    val id: Int,
    @JsonProperty("type")  val type: String,
    @JsonProperty("url")   val url: String
)

data class Source(
    @JsonProperty("id")    val id:Int,
    @JsonProperty("type")  val type:String,
    @JsonProperty("url")   val url:String
)

data class RecSearch(
    @JsonProperty("channels") val channels:List<RecItem>? = emptyList(),
    @JsonProperty("posters")  val posters:List<RecItem>?  = emptyList(),
)

data class RecDizi(
    @JsonProperty("id")       val id:Int,
    @JsonProperty("title")    val title:String,
    @JsonProperty("episodes") val episodes:List<RecEpisode>,
)

data class RecEpisode(
    @JsonProperty("id")       val id:Int,
    @JsonProperty("title")    val title:String,
    @JsonProperty("sources")  val sources: List<Source>
)