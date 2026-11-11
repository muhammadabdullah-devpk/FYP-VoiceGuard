package com.voiceguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voiceguard.app.audio.FileAnalyzer
import com.voiceguard.app.audio.VoiceGuardService
import com.voiceguard.app.databinding.ActivityMainBinding
import com.voiceguard.app.ml.VoskModels
import com.voiceguard.app.model.CaptureMode
import com.voiceguard.app.model.MonitorBus
import com.voiceguard.app.model.MonitorState
import com.voiceguard.app.model.RiskLevel
import com.voiceguard.app.ui.HistoryActivity
import com.voiceguard.app.ui.SettingsActivity
import kotlinx.coroutines.launch

/**
 * Main screen: warning banner with confidence and trend, scam keyword hits, family code-word
 * reminder, and the three capture modes (speakerphone live, recorded call, in-app test call).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: VoiceGuardApp get() = application as VoiceGuardApp

    private var selectedMode = CaptureMode.SPEAKERPHONE
    private var lastState = MonitorState()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMicPermission()) {
            startLiveSession()
        } else {
            Toast.makeText(this, R.string.toast_mic_required, Toast.LENGTH_LONG).show()
        }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) startFileAnalysis(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedMode = app.settings.lastMode
        binding.toggleMode.check(
            when (selectedMode) {
                CaptureMode.SPEAKERPHONE -> R.id.btnModeLive
                CaptureMode.RECORDED_CALL -> R.id.btnModeFile
                CaptureMode.TEST_CALL -> R.id.btnModeTest
            }
        )
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedMode = when (checkedId) {
                R.id.btnModeFile -> CaptureMode.RECORDED_CALL
                R.id.btnModeTest -> CaptureMode.TEST_CALL
                else -> CaptureMode.SPEAKERPHONE
            }
            app.settings.lastMode = selectedMode
            render(lastState)
        }

        binding.btnAction.setOnClickListener { onActionClicked() }
        binding.btnScanText.setOnClickListener { scanTypedText() }
        binding.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MonitorBus.state.collect { render(it) } }
                launch { app.classifier.status.collect { binding.tvModelStatus.text = it.message } }
                launch { VoskModels.status.collect { binding.tvKeywordModelStatus.text = it } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The reminder text can be changed in Settings.
        render(lastState)
    }

    // ------------------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------------------

    private fun onActionClicked() {
        val s = lastState
        if (s.running) {
            if (s.mode == CaptureMode.RECORDED_CALL) FileAnalyzer.cancel() else VoiceGuardService.stop(this)
            return
        }
        when (selectedMode) {
            CaptureMode.RECORDED_CALL ->
                pickAudioLauncher.launch(arrayOf("audio/*", "video/3gpp", "video/mp4"))
            else -> checkPermissionsAndStart()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun checkPermissionsAndStart() {
        val missing = mutableListOf<String>()
        if (!hasMicPermission()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) startLiveSession() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startLiveSession() {
        if (MonitorBus.isBusy()) return
        VoiceGuardService.start(this, selectedMode)
    }

    private fun startFileAnalysis(uri: Uri) {
        if (MonitorBus.isBusy()) return
        FileAnalyzer.start(app, uri, queryName(uri) ?: getString(R.string.default_recording_name))
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun scanTypedText() {
        val text = binding.etTranscript.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show()
            return
        }
        if (lastState.running && lastState.mode == CaptureMode.TEST_CALL) {
            MonitorBus.submitTranscript(text)
            Toast.makeText(this, R.string.toast_text_sent, Toast.LENGTH_SHORT).show()
        } else {
            val hits = app.keywordEngine.scan(text)
            binding.tvKeywordHits.text = if (hits.isEmpty()) {
                getString(R.string.keywords_none)
            } else {
                hits.joinToString("\n") { "• ${it.keyword} (${it.category})" }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------

    private class Look(val bg: Int, val fg: Int, val icon: String, val title: Int)

    private fun lookFor(level: RiskLevel, running: Boolean, mode: CaptureMode): Look = when (level) {
        RiskLevel.SAFE -> Look(R.color.safe_bg, R.color.safe_fg, "✅", R.string.status_safe)
        RiskLevel.CAUTION -> Look(R.color.caution_bg, R.color.caution_fg, "⚠️", R.string.status_caution)
        RiskLevel.ALERT -> Look(R.color.alert_bg, R.color.alert_fg, "🚨", R.string.status_alert)
        RiskLevel.IDLE -> Look(
            R.color.idle_bg, R.color.idle_fg,
            if (running) "🎧" else "🛡️",
            when {
                !running -> R.string.status_idle
                mode == CaptureMode.RECORDED_CALL -> R.string.status_analyzing
                else -> R.string.status_listening
            }
        )
    }

    private fun render(s: MonitorState) {
        lastState = s
        val running = s.running
        val activeMode = if (running) (s.mode ?: selectedMode) else selectedMode

        for (i in 0 until binding.toggleMode.childCount) {
            binding.toggleMode.getChildAt(i).isEnabled = !running
        }

        // Warning banner
        val look = lookFor(s.level, running, activeMode)
        val bg = ContextCompat.getColor(this, look.bg)
        val fg = ContextCompat.getColor(this, look.fg)
        binding.cardStatus.setCardBackgroundColor(bg)
        binding.tvStatusIcon.text = look.icon
        binding.tvStatusTitle.text = getString(look.title)
        binding.tvStatusTitle.setTextColor(fg)
        binding.tvStatusDetail.text = s.message

        if (s.windowsScored > 0) {
            val percent = (s.spoofScore * 100).toInt().coerceIn(0, 100)
            binding.tvConfidence.text = getString(R.string.confidence_fmt, percent, s.trend.label)
            binding.progressConfidence.setIndicatorColor(fg)
            binding.progressConfidence.setProgressCompat(percent, true)
            val note = if (s.avgLatencyMs < 300f) getString(R.string.latency_ok) else getString(R.string.latency_slow)
            binding.tvMetrics.text = getString(
                R.string.metrics_fmt, s.windowsScored, s.lastLatencyMs, s.avgLatencyMs.toInt(), note
            )
        } else {
            binding.tvConfidence.text = getString(R.string.confidence_none)
            binding.progressConfidence.setProgressCompat(0, true)
            binding.tvMetrics.text =
                if (running && !s.modelReady) getString(R.string.no_model_hint) else ""
        }

        // Family code-word reminder, highlighted when there is an alert
        binding.tvFamilyReminder.text = app.settings.familyReminder
        binding.cardFamilyPrompt.setCardBackgroundColor(
            ContextCompat.getColor(this, if (s.level == RiskLevel.ALERT) R.color.family_bg_alert else R.color.family_bg)
        )

        // Scam keywords (second signal)
        binding.tvKeywordHits.text = if (s.keywordHits.isEmpty()) {
            getString(R.string.keywords_none)
        } else {
            val lines = s.keywordHits.joinToString("\n") { "• $it" }
            if (s.keywordWeight >= 3) lines + "\n" + getString(R.string.keywords_strong) else lines
        }

        // Mode selector, description and action button
        binding.tvModeDescription.text = getString(
            when (selectedMode) {
                CaptureMode.SPEAKERPHONE -> R.string.mode_live_desc
                CaptureMode.RECORDED_CALL -> R.string.mode_file_desc
                CaptureMode.TEST_CALL -> R.string.mode_test_desc
            }
        )
        binding.btnAction.text = getString(
            when {
                running && activeMode == CaptureMode.RECORDED_CALL -> R.string.btn_cancel_analysis
                running && activeMode == CaptureMode.TEST_CALL -> R.string.btn_stop_test
                running -> R.string.btn_stop_monitor
                selectedMode == CaptureMode.RECORDED_CALL -> R.string.btn_select_file
                selectedMode == CaptureMode.TEST_CALL -> R.string.btn_start_test
                else -> R.string.btn_start_monitor
            }
        )
        binding.btnAction.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.text_muted else R.color.primary)
        )

        val showFileProgress = running && activeMode == CaptureMode.RECORDED_CALL && s.progress >= 0f
        binding.progressFile.visibility = if (showFileProgress) android.view.View.VISIBLE else android.view.View.GONE
        if (showFileProgress) binding.progressFile.setProgressCompat((s.progress * 100).toInt(), true)

        binding.cardSimulator.visibility =
            if (selectedMode == CaptureMode.TEST_CALL) android.view.View.VISIBLE else android.view.View.GONE
    }
}
