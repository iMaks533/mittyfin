package app.mittyfin.ui.home

import app.mittyfin.data.Item

/** Where a card sits on the home screen: decides what its long-press menu offers. */
enum class HomeSection { HERO, LIBRARY, RESUME, NEXT_UP, LATEST }

enum class HomeAction { OPEN, OPEN_SERIES, REMOVE_FROM_RESUME, HIDE_FROM_NEXT_UP, MARK_PLAYED, MARK_UNPLAYED, ADD_FAVORITE, REMOVE_FAVORITE, HIDE_LIBRARY }

/** The long-press menu of home cards and the filters for what was hidden from it. Free of Android types for tests. */
object HomeMenu {
    /** Hidden next-up suggestions kept; older ones are long gone from next up anyway. */
    const val NEXT_UP_HIDDEN_LIMIT = 200

    fun actions(item: Item, section: HomeSection): List<HomeAction> = buildList {
        add(HomeAction.OPEN)
        if (section == HomeSection.LIBRARY) {
            add(HomeAction.HIDE_LIBRARY)
            return@buildList
        }
        if (item.isEpisode && item.seriesId != null) add(HomeAction.OPEN_SERIES)
        if (section == HomeSection.RESUME) add(HomeAction.REMOVE_FROM_RESUME)
        if (section == HomeSection.NEXT_UP) add(HomeAction.HIDE_FROM_NEXT_UP)
        add(if (item.userData?.played == true) HomeAction.MARK_UNPLAYED else HomeAction.MARK_PLAYED)
        add(if (item.userData?.isFavorite == true) HomeAction.REMOVE_FAVORITE else HomeAction.ADD_FAVORITE)
    }

    fun label(action: HomeAction, section: HomeSection): String = when (action) {
        HomeAction.OPEN -> if (section == HomeSection.LIBRARY) "Открыть медиатеку" else "Открыть"
        HomeAction.OPEN_SERIES -> "Открыть сериал"
        HomeAction.REMOVE_FROM_RESUME -> "Убрать из «Продолжить просмотр»"
        HomeAction.HIDE_FROM_NEXT_UP -> "Скрыть из «Следующих серий»"
        HomeAction.MARK_PLAYED -> "Отметить просмотренным"
        HomeAction.MARK_UNPLAYED -> "Отметить непросмотренным"
        HomeAction.ADD_FAVORITE -> "В избранное"
        HomeAction.REMOVE_FAVORITE -> "Убрать из избранного"
        HomeAction.HIDE_LIBRARY -> "Скрыть медиатеку с главной"
    }

    /** Menu title: the series and episode for episodes, the name otherwise. */
    fun title(item: Item): String =
        if (item.isEpisode) listOfNotNull(item.seriesName, item.episodeLabel).joinToString(" · ").ifBlank { item.name } else item.name

    fun hideNextUp(hidden: List<String>, episodeId: String): List<String> =
        (hidden - episodeId + episodeId).takeLast(NEXT_UP_HIDDEN_LIMIT)

    fun visibleViews(views: List<Item>, hidden: Set<String>): List<Item> = views.filter { it.id !in hidden }

    fun visibleLatest(latest: List<Pair<Item, List<Item>>>, hidden: Set<String>): List<Pair<Item, List<Item>>> =
        latest.filter { it.first.id !in hidden }

    fun visibleNextUp(nextUp: List<Item>, hidden: List<String>): List<Item> {
        if (hidden.isEmpty()) return nextUp
        val set = hidden.toSet()
        return nextUp.filter { it.id !in set }
    }
}
