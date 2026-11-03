package ink.courtpace.index

import android.app.Application
import ink.courtpace.index.data.CourtRepository
import ink.courtpace.index.data.SettingsRepository

class CourtPaceApplication : Application() {

    lateinit var courts: CourtRepository
        private set

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        courts = CourtRepository(this)
        settings = SettingsRepository(this)
    }
}
