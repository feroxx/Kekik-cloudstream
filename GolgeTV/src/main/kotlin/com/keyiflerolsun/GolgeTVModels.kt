package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MainPageResp(
    val ormoxChnlx: List<OrmoxChnlx>,
)

data class OrmoxChnlx(
    val id: String,
    val isim: String,
    val resim: String,
    val link: String,
    val kategori: String,
    val player: String,
    val tip: String,
    val userAgent: String,
    val h1Key: String,
    val h2Key: String,
    val h3Key: String,
    val h4Key: String,
    val h1Val: String,
    val h2Val: String,
    val h3Val: String,
    val h4Val: String,
    val h5Key: String,
    val h5Val: String,
)

data class Golge16FirstResponse(
    @JsonProperty("api_url")
    val apiUrl: String,
    val medyaurl: String,
    val proxyurl: String,
    val headers: Headers,
    @JsonProperty("json_data")
    val jsonData: Any,
)

data class Headers(
    @JsonProperty("user-agent")
    val userAgent: String,
    val referer: String,
    val origin: String,
    @JsonProperty("x-requested-with")
    val xRequestedWith: String,
    @JsonProperty("x-forwarded-for")
    val xForwardedFor: String,
    @JsonProperty("content-type")
    val contentType: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Golge16SecondResponse(
    val addonSig: String,
)