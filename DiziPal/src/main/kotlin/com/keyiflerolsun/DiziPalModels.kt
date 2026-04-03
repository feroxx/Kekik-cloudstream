// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("object_id") 
    val id: Int? = null,
    
    @JsonProperty("object_name") 
    val title: String? = null,
    
    @JsonProperty("object_alternative_name") 
    val trTitle: String? = null,
    
    @JsonProperty("object_poster_url") 
    val poster: String? = null,
    
    // JSON'da hem object_categories hem de object_logo_url (saçma bir şekilde) kategori içeriyor
    @JsonProperty("object_categories") 
    val genres: String? = null,
    
    @JsonProperty("object_related_imdb_point") 
    val imdb: Double? = null,
    
    @JsonProperty("object_release_year") 
    val year: Int? = null,
    
    @JsonProperty("used_type") 
    val type: String? = null,
    
    // Asıl linki oluşturacağımız parçacık
    @JsonProperty("used_slug") 
    val slug: String? = null
)
