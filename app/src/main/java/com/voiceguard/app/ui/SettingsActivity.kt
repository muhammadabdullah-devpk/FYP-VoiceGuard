package com.voiceguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voiceguard.app.R
import com.voiceguard.app.VoiceGuardApp
import com.voiceguard.app.audio.AlertNotifier
import com.voiceguard.app.audio.OverlayBanner
import com.voiceguard.app.databinding.ActivitySettingsBinding
import com.voiceguard.app.ml.BenchmarkResult
import com.voiceguard.app.ml.VoskModels
import com.voiceguard.app.model.AlertEvent
import com.voiceguard.app.model.MonitorBus
import com.voiceguard.app.model.Trend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val app: VoiceGuardApp get() = application as VoiceGuardApp
    private var loading = true
    private var waitingForOverlayPermission = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { sendTestAlert() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val settings = app.settings

        binding.btnBack.setOnClickListener { finish() }

        // Alert threshold
        val thresholdPercent = (settings.threshold * 100).toInt().coerceIn(30, 90)
        binding.seekThreshold.progress = thresholdPercent
        binding.tvThresholdValue.text = getString(R.string.set_threshold_fmt, thresholdPercent)
        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThresholdValue.text = getString(R.string.set_threshold_fmt, progress)
                if (!loading) settings.threshold = progress / 100f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Window length
        binding.radioWindow.check(
            when (settings.windowSeconds) {
                2 -> R.id.rbWin2
                3 -> R.id.rbWin3
                else -> R.id.rbWin4
            }
        )
        binding.radioWindow.setOnCheckedChangeListener { _, id ->
            if (loading) return@setOnCheckedChangeListener
            settings.windowSeconds = when (id) {
                R.id.rbWin2 -> 2
                R.id.rbWin3 -> 3
                else -> 4
            }
        }

        // Audio source (capture-mode study)
        binding.radioSource.check(
            when (settings.audioSourceChoice) {
                1 -> R.id.rbSrcVoiceRec
                2 -> R.id.rbSrcUnprocessed
                else -> R.id.rbSrcMic
            }
        )
        binding.radioSource.setOnCheckedChangeListener { _, id ->
            if (loading) return@setOnCheckedChangeListener
            settings.audioSourceChoice = when (id) {
                R.id.rbSrcVoiceRec -> 1
                R.id.rbSrcUnprocessed -> 2
                else -> 0
            }
        }

        // Alerts
        binding.etFamilyReminder.setText(settings.familyReminder)
        binding.switchVibrate.isChecked = settings.vibrate
        binding.switchVibrate.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.vibrate = checked
        }
        binding.switchOverlay.isChecked = settings.overlayEnabled && OverlayBanner.canShow(this)
        binding.switchOverlay.setOnCheckedChangeListener { button, checked ->
            if (loading) return@setOnCheckedChangeListener
            if (checked && !OverlayBanner.canShow(this)) {
                button.isChecked = false
                waitingForOverlayPermission = true
                Toast.makeText(this, R.string.toast_overlay_permission, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                settings.overlayEnabled = checked
            }
        }
        binding.btnTestAlert.setOnClickListener { requestNotificationThenTestAlert() }

        // Keywords
        binding.switchKeywords.isChecked = settings.keywordsEnabled
        binding.switchKeywords.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            settings.keywordsEnabled = checked
            if (checked) app.appScope.launch { VoskModels.prepare(applicationContext) }
        }
        binding.btnManageKeywords.setOnClickListener {
            startActivity(Intent(this, KeywordsActivity::class.java))
        }

        // Model and benchmark
        binding.btnBenchmark.setOnClickListener { runBenchmark() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.classifier.status.collect { binding.tvModelInfo.text = it.message } }
                launch { VoskModels.status.collect { binding.tvKeywordModelInfo.text = it } }
            }
        }
        loading = false
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission && OverlayBanner.canShow(this)) {
            waitingForOverlayPermission = false
            app.settings.overlayEnabled = true
            loading = true
            binding.switchOverlay.isChecked = true
            loading = false
        }
    }

    override fun onPause() {
        app.settings.familyReminder = binding.etFamilyReminder.text.toString()
        super.onPause()
    }

    private fun requestNotificationThenTestAlert() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            sendTestAlert()
        }
    }

    private fun sendTestAlert() {
        app.settings.familyReminder = binding.etFamilyReminder.text.toString()
        AlertNotifier.showAlert(
            this,
            AlertEvent(0.87f, Trend.RISING, getString(R.string.test_alert_reason)),
            app.settings
        )
        Toast.makeText(this, R.string.toast_test_alert_sent, Toast.LENGTH_SHORT).show()
    }

    private fun runBenchmark() {
        if (MonitorBus.isBusy()) {
            Toast.makeText(this, R.string.toast_busy, Toast.LENGTH_SHORT).show()
            return
        }
        if (!app.classifier.isReady()) {
            binding.tvBenchmarkResult.text = getString(R.string.benchmark_no_model)
            return
        }
        binding.btnBenchmark.isEnabled = false
        binding.tvBenchmarkResult.text = getString(R.string.benchmark_running)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { app.classifier.benchmark(30) }
            binding.btnBenchmark.isEnabled = true
            if (result == null) {
                binding.tvBenchmarkResult.text = getString(R.string.benchmark_no_model)
            } else {
                showBenchmark(result)
            }
        }
    }

    private fun showBenchmark(r: BenchmarkResult) {
        val verdict = getString(if (r.meetsTarget) R.string.benchmark_pass else R.string.benchmark_fail)
        val text = getString(
            R.string.benchmark_result_fmt,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            r.runs,
            r.meanMs,
            r.medianMs,
            r.p95Ms,
            r.maxMs,
            verdict
        )
        binding.tvBenchmarkResult.text = text
        AlertDialog.Builder(this)
            .setTitle(R.string.set_benchmark)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
