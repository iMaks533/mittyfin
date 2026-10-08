package app.mittyfin.ui.home

import app.mittyfin.data.Item
import app.mittyfin.data.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMenuTest {
    private val movie = Item(id = "m", name = "2 Guns", type = "Movie")
    private val episode = Item(
        id = "e", name = "Маомао", type = "Episode", seriesId = "s", seriesName = "The Apothecary Diaries",
        parentIndexNumber = 1, indexNumber = 1,
    )
    private val library = Item(id = "lib", name = "Movies", type = "CollectionFolder", collectionType = "movies")

    @Test fun resumeCardCanBeRemovedFromContinueWatching() {
        assertEquals(
            listOf(HomeAction.OPEN, HomeAction.REMOVE_FROM_RESUME, HomeAction.MARK_PLAYED, HomeAction.ADD_FAVORITE),
            HomeMenu.actions(movie, HomeSection.RESUME)
        )
    }

    @Test fun nextUpEpisodeCanBeHiddenAndOpensItsSeries() {
        assertEquals(
            listOf(HomeAction.OPEN, HomeAction.OPEN_SERIES, HomeAction.HIDE_FROM_NEXT_UP, HomeAction.MARK_PLAYED, HomeAction.ADD_FAVORITE),
            HomeMenu.actions(episode, HomeSection.NEXT_UP)
        )
    }

    @Test fun playedAndFavoriteFlipTheirEntries() {
        val seen = movie.copy(userData = UserData(played = true, isFavorite = true))
        val actions = HomeMenu.actions(seen, HomeSection.LATEST)
        assertTrue(HomeAction.MARK_UNPLAYED in actions && HomeAction.REMOVE_FAVORITE in actions)
        assertTrue(HomeAction.REMOVE_FROM_RESUME !in actions && HomeAction.HIDE_FROM_NEXT_UP !in actions)
    }

    @Test fun libraryTileOnlyOpensOrHides() {
        assertEquals(listOf(HomeAction.OPEN, HomeAction.HIDE_LIBRARY), HomeMenu.actions(library, HomeSection.LIBRARY))
        assertEquals("Открыть медиатеку", HomeMenu.label(HomeAction.OPEN, HomeSection.LIBRARY))
        assertEquals("Открыть", HomeMenu.label(HomeAction.OPEN, HomeSection.HERO))
    }

    @Test fun titleNamesTheSeriesForEpisodes() {
        assertEquals("The Apothecary Diaries · S1E1 : Маомао", HomeMenu.title(episode))
        assertEquals("2 Guns", HomeMenu.title(movie))
    }

    @Test fun hiddenLibraryDropsItsTileAndLatestRow() {
        val other = library.copy(id = "tv", name = "Series")
        assertEquals(listOf(other), HomeMenu.visibleViews(listOf(library, other), setOf("lib")))
        val latest = listOf(library to listOf(movie), other to listOf(episode))
        assertEquals(listOf(other to listOf(episode)), HomeMenu.visibleLatest(latest, setOf("lib")))
    }

    @Test fun hiddenNextUpIsFilteredByEpisode() {
        val next = episode.copy(id = "e2")
        assertEquals(listOf(next), HomeMenu.visibleNextUp(listOf(episode, next), listOf("e")))
        assertEquals(listOf(episode, next), HomeMenu.visibleNextUp(listOf(episode, next), emptyList()))
    }

    @Test fun hidingNextUpKeepsTheNewestAndNoDuplicates() {
        assertEquals(listOf("b", "a"), HomeMenu.hideNextUp(listOf("a", "b"), "a"))
        val full = (1..HomeMenu.NEXT_UP_HIDDEN_LIMIT).map { "x$it" }
        val after = HomeMenu.hideNextUp(full, "new")
        assertEquals(HomeMenu.NEXT_UP_HIDDEN_LIMIT, after.size)
        assertEquals("new", after.last())
        assertTrue("x1" !in after)
    }
}
