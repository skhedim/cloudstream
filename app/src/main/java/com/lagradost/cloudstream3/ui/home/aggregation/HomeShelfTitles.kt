package com.lagradost.cloudstream3.ui.home.aggregation

import com.lagradost.cloudstream3.R

/** Android titles of the shared home shelves. */
val HomeShelf.title: Int
    get() = when (this) {
        HomeShelf.Top10 -> R.string.home_top10
        HomeShelf.Recent -> R.string.home_recent_additions
        HomeShelf.Trending -> R.string.home_trending
        HomeShelf.ForYou -> R.string.home_for_you_movies
        HomeShelf.ForYouSeries -> R.string.home_for_you_series
        HomeShelf.Movies -> R.string.home_movies
        HomeShelf.Series -> R.string.home_series
        HomeShelf.Anime -> R.string.home_anime
        HomeShelf.Documentaries -> R.string.home_documentaries
        HomeShelf.Action -> R.string.home_action
        HomeShelf.Comedy -> R.string.home_comedy
        HomeShelf.Thriller -> R.string.home_thriller
        HomeShelf.ScienceFiction -> R.string.home_scifi
        HomeShelf.Discover -> R.string.home_discover
    }
