package com.voiceguard.app

import android.app.Application
import com.voiceguard.app.audio.AlertNotifier
import com.voiceguard.app.data.AppDatabase
import com.voiceguard.app.data.DefaultKeywords
import com.voiceguard.app.data.SettingsStore
import com.voiceguard.app.ml.ScamKeywordDetector
import com.voiceguard.app.ml.VoiceGuardClassifier
import com.voiceguard.app.ml.VoskModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds the objects that are shared by all screens and by the foreground service. */
class VoiceGuardApp : Application() {

    /** Lives as long as the process. Used for work that must survive screen rotation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var classifier: VoiceGuardClassifier
        private set
    lateinit var keywordEngine: ScamKeywordDetector
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        settings = SettingsStore(this)
        classifier = VoiceGuardClassifier(this)
        keywordEngine = ScamKeywordDetector()
        AlertNotifier.createChannels(this)

        appScope.launch(Dispatchers.IO) {
            db.sessions().closeStaleSessions()
            if (db.keywords().count() == 0) db.keywords().insertAll(DefaultKeywords.build())
            reloadKeywords()
            classifier.load()
            if (settings.keywordsEnabled) VoskModels.prepare(applicationContext)
        }
    }

    /** Call after the keyword list changed. */
    suspend fun reloadKeywords() {
        keywordEngine.reload(db.keywords().getAll())
    }
}
