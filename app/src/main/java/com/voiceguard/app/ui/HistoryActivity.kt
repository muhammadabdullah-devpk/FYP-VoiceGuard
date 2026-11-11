package com.voiceguard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.voiceguard.app.R
import com.voiceguard.app.VoiceGuardApp
import com.voiceguard.app.data.CallSession
import com.voiceguard.app.databinding.ActivityHistoryBinding
import com.voiceguard.app.model.MonitorBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** History of analysed calls (stored on the phone: scores, alerts, keyword hits, no audio). */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val app: VoiceGuardApp get() = application as VoiceGuardApp

    private val adapter = SessionAdapter { session ->
        startActivity(
            Intent(this, SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_ID, session.id)
        )
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) exportCsv(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerSessions.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnExportCsv.setOnClickListener { exportLauncher.launch("voiceguard_results.csv") }
        binding.btnClearHistory.setOnClickListener { confirmClear() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.db.sessions().observeAll().collect { list ->
                    adapter.submit(list)
                    binding.tvHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    refreshSummary(list)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Alerts can be marked as false alarms in the detail screen.
        lifecycleScope.launch { refreshSummary(app.db.sessions().getAll()) }
    }

    private suspend fun refreshSummary(list: List<CallSession>) {
        val (alerts, falseAlerts) = withContext(Dispatchers.IO) {
            app.db.alerts().countAll() to app.db.alerts().countFalse()
        }
        val withAlerts = list.count { it.alertCount > 0 }
        val rate = if (alerts > 0) falseAlerts * 100 / alerts else 0
        binding.tvHistorySummary.text =
            getString(R.string.history_summary_fmt, list.size, withAlerts, alerts, falseAlerts, rate)
    }

    private fun confirmClear() {
        if (MonitorBus.isBusy()) {
            Toast.makeText(this, R.string.toast_busy, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_clear_history)
            .setMessage(R.string.clear_history_message)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { app.db.sessions().deleteAll() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One row per scored window, with the session columns repeated (easy to load in Python). */
    private fun exportCsv(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val sessions = app.db.sessions().getAll().associateBy { it.id }
                    val windows = app.db.windows().getAll()
                    val alertCounts = app.db.alerts().countsPerSession().associateBy { it.sessionId }
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                        w.write(
                            "session_id,started_at,capture_mode,source,verdict,session_alerts," +
                                "session_false_alarms,window_index,start_ms,raw_spoof_score," +
                                "smoothed_spoof_score,trend,latency_ms\n"
                        )
                        for (win in windows) {
                            val s = sessions[win.sessionId] ?: continue
                            val c = alertCounts[s.id]
                            w.write(
                                listOf(
                                    s.id.toString(),
                                    fmt.format(Date(s.startedAt)),
                                    s.captureMode,
                                    csv(s.sourceLabel),
                                    s.verdict,
                                    (c?.total ?: 0).toString(),
                                    (c?.falseCount ?: 0).toString(),
                                    win.windowIndex.toString(),
                                    win.startMs.toString(),
                                    "%.4f".format(Locale.US, win.rawScore),
                                    "%.4f".format(Locale.US, win.smoothedScore),
                                    win.trend,
                                    win.latencyMs.toString()
                                ).joinToString(",") + "\n"
                            )
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@HistoryActivity,
                if (ok) R.string.toast_export_ok else R.string.toast_export_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
