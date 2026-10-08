package app.mittyfin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Subset of the Jellyfin REST DTOs (PascalCase JSON) the app needs.

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)

@Serializable
data class AuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<Item> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable
data class UserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)

@Serializable
data class MediaStream(
    @SerialName("Type") val type: String = "",
    @SerialName("Index") val index: Int = -1,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("DvProfile") val dvProfile: Int? = null,
    @SerialName("DvLevel") val dvLevel: Int? = null,
    @SerialName("ElPresentFlag") val elPresentFlag: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
)

@Serializable
data class MediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
)

@Serializable
data class Item(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("CommunityRating") val communityRating: Float? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("UserData") val userData: UserData? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource> = emptyList(),
    @SerialName("ChildCount") val childCount: Int? = null,
) {
    val isSeries: Boolean get() = type == "Series"
    val isEpisode: Boolean get() = type == "Episode"
    val isFolderLike: Boolean get() = type == "Series" || type == "Season" || type == "BoxSet" || type == "CollectionFolder"
    val runtimeMs: Long get() = (runTimeTicks ?: 0L) / 10_000L
    val positionMs: Long get() = (userData?.playbackPositionTicks ?: 0L) / 10_000L

    /** "S3E15 : Episode 15" for episodes. */
    val episodeLabel: String?
        get() = if (!isEpisode) null else buildString {
            parentIndexNumber?.let { append("S").append(it) }
            indexNumber?.let { append("E").append(it) }
            if (isNotEmpty()) append(" : ")
            append(name)
        }

    /** "2023 - Present" / "2019 - 2022" for series, the year otherwise. */
    val yearLabel: String?
        get() {
            val start = productionYear ?: return null
            if (!isSeries) return start.toString()
            return when {
                status == "Continuing" -> "$start - Present"
                endDate != null -> {
                    val end = endDate.take(4)
                    if (end == start.toString()) end else "$start - $end"
                }
                else -> start.toString()
            }
        }
}
