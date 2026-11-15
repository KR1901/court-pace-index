package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.CachedLiveRating

/** Where the last live rating is kept between launches. */
class LiveRatingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("live-rating", Context.MODE_PRIVATE)

    fun read(tournamentId: String): CachedLiveRating? =
        CachedLiveRating.fromJson(prefs.getString(KEY_PREFIX + tournamentId, null))

    /** Every locally retained event rating, including events that have left the live slate. */
    fun readAll(): List<CachedLiveRating> =
        prefs.all.entries.asSequence()
            .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value is String }
            .mapNotNull { (_, value) -> CachedLiveRating.fromJson(value as? String) }
            .toList()

    fun write(rating: CachedLiveRating) {
        prefs.edit().putString(KEY_PREFIX + rating.tournamentId, rating.toJson()).apply()
    }

    private companion object {
        const val KEY_PREFIX = "rating:"
    }
}
