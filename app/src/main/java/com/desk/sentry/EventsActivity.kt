package com.desk.sentry

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class EventsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SentryService.isEventsActivityVisible = true
        SentryService.lastAppActiveTimestamp = System.currentTimeMillis()

        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(rootLayout)

        showDatesListView()
    }

    override fun onDestroy() {
        super.onDestroy()
        SentryService.isEventsActivityVisible = false
        SentryService.lastAppActiveTimestamp = System.currentTimeMillis()
    }

    /**
     * SCREEN 1: DATES SELECTOR LIST
     */
    private fun showDatesListView() {
        rootLayout.removeAllViews()

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 18, 24, 18)
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = "📊 Desk Sentry Study Logs"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = Button(this).apply {
            text = "✕ Close"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { finish() }
        }

        topBar.addView(tvTitle)
        topBar.addView(btnClose)
        rootLayout.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 24)
        }

        val datesSet = prefs.getStringSet("event_dates_set", HashSet()) ?: HashSet()

        if (datesSet.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No study records found yet.\nComplete your first session to view analytics."
                setTextColor(Color.GRAY)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 100, 0, 0)
            }
            container.addView(emptyTv)
        } else {
            val sortedDates = datesSet.sortedDescending()
            for (dateKey in sortedDates) {
                val raw = prefs.getString("event_data_$dateKey", null) ?: continue
                val json = JSONObject(raw)
                val dayName = json.optString("dayName", dateKey)

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#1E293B"))
                    setPadding(20, 16, 20, 16)
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.setMargins(0, 0, 0, 12)
                    layoutParams = params
                }

                val tvDate = TextView(this).apply {
                    text = "📅 $dayName"
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnOpenReport = Button(this).apply {
                    text = "View Full Report →"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#0284C7"))
                    setOnClickListener {
                        showAllDayFullReportScreen(dateKey, dayName, json)
                    }
                }

                card.addView(tvDate)
                card.addView(btnOpenReport)
                container.addView(card)
            }
        }

        scrollView.addView(container)
        rootLayout.addView(scrollView)
    }

    /**
     * SCREEN 2: ALL-DAY FULL REPORT WITH PERMANENT TOP-RIGHT PINNED STICKY DATE
     */
    private fun showAllDayFullReportScreen(dateKey: String, dayName: String, json: JSONObject) {
        rootLayout.removeAllViews()

        // ============================================================
        // 1. FIXED TOP APP BAR (HOLDING TITLE ON LEFT + DATE ON RIGHT)
        // ============================================================
        val topAppBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 12, 20, 12)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = Button(this).apply {
            text = "←"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#334155"))
            val btnParams = LinearLayout.LayoutParams(54, 40)
            btnParams.marginEnd = 14
            layoutParams = btnParams
            setOnClickListener { showDatesListView() }
        }

        val tvReportHeading = TextView(this).apply {
            text = "All-Day Full Report"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // PERMANENT STICKY DATE IN THE TOP-RIGHT CORNER (NEVER SCROLLS AWAY)
        val tvStickyDateBadge = TextView(this).apply {
            text = "📅 $dayName"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#312E81"))
                cornerRadius = 8f
                setStroke(1, Color.parseColor("#6366F1"))
            }
            setPadding(14, 6, 14, 6)
        }

        topAppBar.addView(btnBack)
        topAppBar.addView(tvReportHeading)
        topAppBar.addView(tvStickyDateBadge)
        rootLayout.addView(topAppBar)

        // ============================================================
        // 2. SCROLLABLE CONTENT (SUMMARY CARD + SLOTS 1 TO 5)
        // ============================================================
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 20)
        }

        val slots = json.optJSONObject("slots") ?: JSONObject()
        var totalPresentSec = 0L
        var totalAbsentSec = 0L
        var totalBreakSec = 0L

        // CALCULATE TOTALS FIRST
        for (slotNum in 1..5) {
            val slotObj = slots.optJSONObject(slotNum.toString()) ?: continue
            totalPresentSec += slotObj.optLong("presentSec", 0L)
            totalAbsentSec += slotObj.optLong("absentSec", 0L)
            totalBreakSec += slotObj.optLong("officialBreakSec", 0L)
        }

        // TOTAL DAY SUMMARY CARD (TEAL/GREEN BANNER AT THE TOP OF SCROLL)
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F766E"))
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F766E"))
                cornerRadius = 12f
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
        }

        val tvStudy = TextView(this).apply {
            text = "🏆 Total Study Time: ${formatSec(totalPresentSec)}"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }

        val tvBreaks = TextView(this).apply {
            text = "☕ Total Official Breaks: ${formatSec(totalBreakSec)}"
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 11f
            setPadding(0, 3, 0, 2)
        }

        val tvAway = TextView(this).apply {
            text = "⚠️ Unexcused Away: ${formatSec(totalAbsentSec)}"
            setTextColor(Color.parseColor("#FECACA"))
            textSize = 11f
        }

        summaryCard.addView(tvStudy)
        summaryCard.addView(tvBreaks)
        summaryCard.addView(tvAway)
        contentLayout.addView(summaryCard)

        // INDIVIDUAL SLOTS CARDS (SLOT 1 TO SLOT 5)
        for (slotNum in 1..5) {
            val slotObj = slots.optJSONObject(slotNum.toString())
            val presentSec = slotObj?.optLong("presentSec", 0L) ?: 0L
            val absentSec = slotObj?.optLong("absentSec", 0L) ?: 0L
            val breakSec = slotObj?.optLong("officialBreakSec", 0L) ?: 0L

            val slotCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 10f
                    setStroke(1, Color.parseColor("#334155"))
                }
                setPadding(16, 10, 16, 10)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 8)
                layoutParams = params
            }

            val tvSlotTitle = TextView(this).apply {
                text = "📘 Slot $slotNum Summary"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }

            val tvSlotStats = TextView(this).apply {
                text = "Study: ${formatSec(presentSec)} | Breaks: ${formatSec(breakSec)} | Away: ${formatSec(absentSec)}"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 10f
                setPadding(0, 3, 0, 3)
            }

            slotCard.addView(tvSlotTitle)
            slotCard.addView(tvSlotStats)

            // UNEXCUSED ABSENCE INTERVALS BREAKDOWN (IF ANY)
            val absences = slotObj?.optJSONArray("absences")
            if (absences != null && absences.length() > 0) {
                for (j in 0 until absences.length()) {
                    val item = absences.getJSONObject(j)
                    val reason = item.optString("reason", "Absent")
                    val tvInterval = TextView(this).apply {
                        text = "  ⚠️ ${item.optString("start")} – ${item.optString("end")} (${formatSec(item.optLong("durationSec"))}) [$reason]"
                        setTextColor(Color.parseColor("#F87171"))
                        textSize = 9sp
                    }
                    slotCard.addView(tvInterval)
                }
            }

            contentLayout.addView(slotCard)
        }

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)
    }

    private fun formatSec(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%dh %02dm %02ds", h, m, s) else String.format("%02dm %02ds", m, s)
    }
}
