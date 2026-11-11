package com.voiceguard.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.voiceguard.app.R
import com.voiceguard.app.VoiceGuardApp
import com.voiceguard.app.databinding.ActivitySessionDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Alerts, keyword hits and window scores of one session. Alerts can be marked as false alarms. */
class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private val app: VoiceGuardApp get() = application as VoiceGuardApp
    private val adapter = RowAdapter()
    private var sessionId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent.getLongExtra(EXTRA_ID, 0L)
        binding.recyclerDetail.layoutManager = LinearLayoutManager(this)
        binding.recyclerDetail.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val session = app.db.sessions().get(sessionId)
                Triple(
                    session,
                    app.db.alerts().forSession(sessionId),
                    Pair(app.db.keywordHits().forSession(sessionId), app.db.windows().forSession(sessionId))
                )
            }
            val session = data.first
            if (session == null) {
                finish()
                return@launch
            }
            val alerts = data.second
            val hits = data.third.first
            val windows = data.third.second

            binding.tvDetailTitle.text = SessionAdapter.modeLabel(session.captureMode)
            binding.tvDetailSummary.text = getString(
                R.string.detail_summary_fmt,
                SessionAdapter.formatTime(session.startedAt),
                session.sourceLabel,
                SessionAdapter.verdictLabel(session.verdict),
                session.windowCount,
                (session.avgSpoofScore * 100).toInt(),
                (session.maxSpoofScore * 100).toInt(),
                session.avgLatencyMs.toInt()
            )

            val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val rows = ArrayList<Row>()

            rows.add(Row(getString(R.string.detail_alerts, alerts.size), header = true))
            if (alerts.isEmpty()) rows.add(Row(getString(R.string.detail_none)))
            for (a in alerts) {
                val flag = if (a.falseAlarm) getString(R.string.detail_marked_false) + "\n" else ""
                rows.add(
                    Row(
                        title = "${clock.format(Date(a.createdAt))}  |  ${(a.confidence * 100).toInt()}% (${a.trend})",
                        subtitle = flag + a.reason,
                        actionLabel = getString(if (a.falseAlarm) R.string.btn_undo else R.string.btn_false_alarm),
                        onAction = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                app.db.alerts().setFalseAlarm(a.id, !a.falseAlarm)
                                withContext(Dispatchers.Main) { load() }
                            }
                        }
                    )
                )
            }

            rows.add(Row(getString(R.string.detail_keywords, hits.size), header = true))
            if (hits.isEmpty()) rows.add(Row(getString(R.string.detail_none)))
            for (h in hits) {
                rows.add(Row("${h.matchedWord}", "${h.category}  |  ${clock.format(Date(h.createdAt))}"))
            }

            rows.add(Row(getString(R.string.detail_windows, windows.size), header = true))
            if (windows.isEmpty()) rows.add(Row(getString(R.string.detail_none)))
            for (w in windows) {
                rows.add(
                    Row(
                        title = getString(R.string.detail_window_title, w.windowIndex + 1, w.startMs / 1000.0),
                        subtitle = getString(
                            R.string.detail_window_sub,
                            (w.rawScore * 100).toInt(),
                            (w.smoothedScore * 100).toInt(),
                            w.latencyMs
                        )
                    )
                )
            }
            adapter.submit(rows)
        }
    }

    companion object {
        const val EXTRA_ID = "session_id"
    }
}
