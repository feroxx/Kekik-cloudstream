// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty

data class GenresMovie(
    @JsonProperty("data") val data: List<JustMovie>
)

data class JustMovie(
    @JsonProperty("id")          val id: Int,
    @JsonProperty("title")       val title: String,
    @JsonProperty("poster_path") val posterPath: String,
    @JsonProperty("type")        val type: String
)

data class GenresSerie(
    @JsonProperty("data") val data: List<JustSerie>
)

data class JustSerie(
    @JsonProperty("id")          val id: Int,
    @JsonProperty("name")        val name: String,
    @JsonProperty("poster_path") val posterPath: String,
    @JsonProperty("type")        val type: String
)

data class MovieDetail(
    @JsonProperty("id")            val id: Int,
    @JsonProperty("title")         val title: String,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("overview")      val overview: String?,
    @JsonProperty("poster_path")   val posterPath: String,
    @JsonProperty("vote_average")  val voteAverage: Int,
    @JsonProperty("release_date")  val releaseDate: String,
    @JsonProperty("casterslist")   val casterslist: List<Cast>?,
    @JsonProperty("relateds")      val relateds: List<JustMovie>?,
    @JsonProperty("genres")        val genres: List<Genre>?,
    @JsonProperty("videos")        val videos: List<Video>
)

data class SerieDetail(
    @JsonProperty("id")             val id: Int,
    @JsonProperty("name")           val name: String,
    @JsonProperty("original_name")  val originalName: String?,
    @JsonProperty("overview")       val overview: String?,
    @JsonProperty("poster_path")    val posterPath: String,
    @JsonProperty("vote_average")   val voteAverage: Int,
    @JsonProperty("first_air_date") val firstAirDate: String,
    @JsonProperty("casterslist")    val casterslist: List<Cast>?,
    @JsonProperty("relateds")       val relateds: List<JustSerie>?,
    @JsonProperty("genres")         val genres: List<Genre>?,
    @JsonProperty("seasons")        val seasons: List<SeasonDetail>
)

data class Cast(
    @JsonProperty("name")         val name: String,
    @JsonProperty("profile_path") val profilePath: String?
)

data class Genre(
    @JsonProperty("name") val name: String
)

data class Video(
    @JsonProperty("id")   val id: Int,
    @JsonProperty("link") val link: String,
    @JsonProperty("lang") val lang: String?
)

data class Search(
    @JsonProperty("search") val search: List<SearchItem>
)

data class SearchItem(
    @JsonProperty("id")          val id: Int,
    @JsonProperty("name")        val name: String,
    @JsonProperty("poster_path") val posterPath: String,
    @JsonProperty("type")        val type: String
)

data class SeasonDetail(
    @JsonProperty("season_number") val seasonNumber: Int,
    @JsonProperty("episodes")      val episodes: List<EpisodeDetail>
)

data class EpisodeDetail(
    @JsonProperty("episode_number") val episodeNumber: Int,
    @JsonProperty("name")           val name: String,
    @JsonProperty("overview")       val overview: String?,
    @JsonProperty("still_path")     val stillPath: String?,
    @JsonProperty("videos")         val videos: List<Video>
)