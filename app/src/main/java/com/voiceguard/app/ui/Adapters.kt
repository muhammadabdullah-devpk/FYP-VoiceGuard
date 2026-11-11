package com.voiceguard.app.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.voiceguard.app.R
import com.voiceguard.app.data.CallSession
import com.voiceguard.app.databinding.ItemRowBinding
import com.voiceguard.app.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A simple list row used by the detail and keyword screens. */
data class Row(
    val title: String,
    val subtitle: String = "",
    val header: Boolean = false,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

    private var rows: List<Row> = emptyList()

    class VH(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val b = holder.binding
        val context = b.root.context

        b.tvRowTitle.text = row.title
        b.tvRowTitle.setTypeface(null, if (row.header) Typeface.BOLD else Typeface.NORMAL)
        b.tvRowTitle.textSize = if (row.header) 16f else 14f
        b.tvRowTitle.setTextColor(ContextCompat.getColor(context, if (row.header) R.color.primary else R.color.text_dark))

        b.tvRowSubtitle.text = row.subtitle
        b.tvRowSubtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE

        if (row.actionLabel != null) {
            b.btnRowAction.visibility = View.VISIBLE
            b.btnRowAction.text = row.actionLabel
            b.btnRowAction.setOnClickListener { row.onAction?.invoke() }
        } else {
            b.btnRowAction.visibility = View.GONE
            b.btnRowAction.setOnClickListener(null)
        }
    }
}

class SessionAdapter(private val onClick: (CallSession) -> Unit) :
    RecyclerView.Adapter<SessionAdapter.VH>() {

    private var sessions: List<CallSession> = emptyList()

    class VH(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<CallSession>) {
        sessions = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = sessions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = sessions[position]
        val b = holder.binding
        val context = b.root.context

        b.tvSessionTitle.text = "${modeLabel(s.captureMode)}  |  ${formatTime(s.startedAt)}"
        b.tvSessionVerdict.text = verdictLabel(s.verdict)
        b.tvSessionVerdict.setTextColor(
            ContextCompat.getColor(
                context,
                when (s.verdict) {
                    "SUSPICIOUS" -> R.color.alert_fg
                    "GENUINE" -> R.color.safe_fg
                    "ERROR" -> R.color.alert_fg
                    else -> R.color.text_muted
                }
            )
        )
        b.tvSessionStats.text = context.getString(
            R.string.session_stats_fmt,
            s.sourceLabel,
            s.windowCount,
            (s.avgSpoofScore * 100).toInt(),
            (s.maxSpoofScore * 100).toInt(),
            s.alertCount,
            s.avgLatencyMs.toInt()
        )
        b.root.setOnClickListener { onClick(s) }
    }

    companion object {
        fun modeLabel(mode: String): String = when (mode) {
            "SPEAKERPHONE" -> "Speakerphone call"
            "RECORDED_CALL" -> "Recorded call"
            "TEST_CALL" -> "Test call"
            else -> mode
        }

        fun verdictLabel(verdict: String): String = when (verdict) {
            "SUSPICIOUS" -> "Suspicious: warning raised"
            "GENUINE" -> "Genuine: no warning"
            "NO_DATA" -> "No score (too little audio or no model)"
            "CANCELLED" -> "Stopped before the end"
            "INTERRUPTED" -> "Interrupted"
            "ERROR" -> "Error"
            "IN_PROGRESS" -> "In progress"
            else -> verdict
        }

        fun formatTime(millis: Long): String =
            SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }
}
