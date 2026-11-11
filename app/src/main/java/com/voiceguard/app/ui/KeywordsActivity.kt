package com.voiceguard.app.ui

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.voiceguard.app.R
import com.voiceguard.app.VoiceGuardApp
import com.voiceguard.app.data.DefaultKeywords
import com.voiceguard.app.data.ScamKeyword
import com.voiceguard.app.databinding.ActivityKeywordsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The scam keyword list (proposal Table 4). The list "will be extended from the collected call
 * data", so the user can add own keywords here. Default keywords cannot be deleted.
 */
class KeywordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeywordsBinding
    private val app: VoiceGuardApp get() = application as VoiceGuardApp
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeywordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerKeywords.layoutManager = LinearLayoutManager(this)
        binding.recyclerKeywords.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddKeyword.setOnClickListener { showAddDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.db.keywords().observeAll().collect { list -> adapter.submit(buildRows(list)) }
            }
        }
    }

    private fun buildRows(list: List<ScamKeyword>): List<Row> {
        val rows = ArrayList<Row>()
        val grouped = list.groupBy { it.category }
        val order = DefaultKeywords.categories + grouped.keys.filter { it !in DefaultKeywords.categories }
        for (category in order) {
            val items = grouped[category] ?: continue
            rows.add(Row("$category (${items.size})", header = true))
            for (k in items) {
                val kind = if (k.isCustom) "custom" else "default"
                rows.add(
                    Row(
                        title = k.keyword,
                        subtitle = "${k.language}  |  weight ${k.weight}  |  $kind",
                        actionLabel = if (k.isCustom) getString(R.string.btn_delete) else null,
                        onAction = if (k.isCustom) {
                            { deleteKeyword(k.id) }
                        } else null
                    )
                )
            }
        }
        return rows
    }

    private fun deleteKeyword(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.db.keywords().deleteCustom(id)
            app.reloadKeywords()
        }
    }

    private fun showAddDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.kw_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@KeywordsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                DefaultKeywords.categories
            )
        }
        val weightSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@KeywordsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("1 (weak hint)", "2 (medium)", "3 (strong)")
            )
            setSelection(1)
        }
        container.addView(input)
        container.addView(categorySpinner)
        container.addView(weightSpinner)

        AlertDialog.Builder(this)
            .setTitle(R.string.btn_add_keyword)
            .setView(container)
            .setPositiveButton(R.string.btn_add) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show()
                } else {
                    val keyword = ScamKeyword(
                        category = DefaultKeywords.categories[categorySpinner.selectedItemPosition],
                        keyword = text,
                        language = "CUSTOM",
                        weight = weightSpinner.selectedItemPosition + 1,
                        isCustom = true
                    )
                    lifecycleScope.launch(Dispatchers.IO) {
                        app.db.keywords().insert(keyword)
                        app.reloadKeywords()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
