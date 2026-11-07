package dev.walden.mygomusic

import kotlinx.serialization.Serializable

// Data model mirroring internal/music/music.go of MyGo Music (JSON fields).

@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val following: Boolean = false,
)

@Serializable
data class Album(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val artists: List<Artist> = emptyList(),
    val released: String? = null,
    val saved: Boolean = false,
)

@Serializable
data class Track(
    val id: String = "",
    val source: String? = null,
    val name: String = "",
    val artists: List<Artist> = emptyList(),
    val album: Album = Album(),
    val duration: Long = 0,
    val url: String? = null,
    val unavailable: Boolean = false,
    val showId: String? = null,
    val description: String? = null,
    val published: String? = null,
) {
    fun artistNames(): String = artists.joinToString(", ") { it.name }
}

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val description: String? = null,
    val owner: String? = null,
    val trackCount: Int = 0,
    val mine: Boolean = false,
    val saved: Boolean = false,
    val fixed: Boolean = false,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class Account(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val page: String? = null,
)

@Serializable
data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val shows: List<ShowItem> = emptyList(),
    val more: Boolean = false,
)

@Serializable
data class TrackPage(
    val tracks: List<Track> = emptyList(),
    val more: Boolean = false,
)

@Serializable
data class AlbumPageResult(
    val album: Album = Album(),
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class ArtistPageResult(
    val artist: Artist = Artist(),
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
)

@Serializable
data class ShowItem(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val publisherPage: String? = null,
    val episodeCount: Int = 0,
    val subscribed: Boolean = false,
)

@Serializable
data class ShowPageResult(
    val show: ShowItem = ShowItem(),
    val episodes: List<Track> = emptyList(),
    val more: Boolean = false,
)

@Serializable
data class Station(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val imageUrl: String? = null,
)

@Serializable
data class LinkItem(
    val title: String = "",
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val page: String = "",
)

@Serializable
data class Section(
    val title: String? = null,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val shows: List<ShowItem> = emptyList(),
    val stations: List<Station> = emptyList(),
    val links: List<LinkItem> = emptyList(),
    val morePage: String? = null,
    val empty: String? = null,
    val pageTitle: String? = null,
    val subtitle: String? = null,
)

@Serializable
data class BrowsePage(
    val title: String? = null,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    val sections: List<Section> = emptyList(),
)

@Serializable
data class StreamInfo(
    val url: String = "",
    val type: String = "",
    val quality: String? = null,
    val bitRate: Int = 0,
    val size: Long = 0,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class LyricWord(
    val start: Long = 0,
    val end: Long = 0,
    val text: String = "",
)

@Serializable
data class LyricLine(
    val start: Long = 0,
    val end: Long = 0,
    val text: String? = null,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
) {
    fun displayText(): String = text ?: words.joinToString("") { it.text }
}

@Serializable
data class Lyrics(
    val lrc: String? = null,
    val translation: String? = null,
    val lines: List<LyricLine> = emptyList(),
)
