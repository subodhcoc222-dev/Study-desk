package com.desk.sentry

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale

class EventsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var contentContainer: LinearLayout
    private lateinit var tvHeaderTitle: TextView
    private lateinit var btnBack: Button

    private var currentLevel = 1
    private var selectedDateKey = ""
    private var selectedDayName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_events)

        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)

        contentContainer = findViewById(R.id.eventsContentContainer)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { handleBackNavigation() }

        showLevel1DateList()
    }

    private fun handleBackNavigation() {
        when (currentLevel) {
            3 -> showLevel2SlotMenu(selectedDateKey, selectedDayName)
            2 -> showLevel1DateList()
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentLevel > 1) {
            handleBackNavigation()
        } else {
            super.onBackPressed()
        }
    }

    private fun getDayJson(dateKey: String): JSONObject {
        val raw = prefs.getString("event_data_$dateKey", null)
        return if (raw != null) JSONObject(raw) else {
            JSONObject().apply {
                put("date", dateKey)
                put("dayName", dateKey)
                put("slots", JSONObject())
            }
        }
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%02dh %02dm %02ds", h, m, s)
        else String.format("%02dm %02ds", m, s)
    }

    /**
     * LEVEL 1: Full-Screen Date List
     */
    private fun showLevel1DateList() {
        currentLevel = 1
        tvHeaderTitle.text = "📅 Recorded Study Dates"
        contentContainer.removeAllViews()

        val dateSet = prefs.getStringSet("event_dates_set", HashSet()) ?: HashSet()
        val sortedDates = dateSet.toMutableList()
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (!sortedDates.contains(todayKey)) sortedDates.add(todayKey)
        sortedDates.sortDescending()

        for (dateKey in sortedDates) {
            val dayJson = getDayJson(dateKey)
            val dayName = dayJson.optString("dayName", dateKey)

            val card = CardView(this).apply {
                radius = 24f
                setCardBackgroundColor(Color.parseColor("#1E293B"))
                cardElevation = 6f
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 16)
                layoutParams = params
            }

            val cardContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }

            val tvDate = TextView(this).apply {
                text = if (dateKey == todayKey) "📍 Today • $dayName" else "📅 $dayName"
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }
            val tvSub = TextView(this).apply {
                text = "Tap to view Slot 1–5 breakdown & logs →"
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 8, 0, 0)
            }

            cardContent.addView(tvDate)
            cardContent.addView(tvSub)
            card.addView(cardContent)

            card.setOnClickListener {
                selectedDateKey = dateKey
                selectedDayName = dayName
                showLevel2SlotMenu(dateKey, dayName)
            }

            contentContainer.addView(card)
        }
    }

    /**
     * LEVEL 2: Slots Menu for Selected Date
     */
    private fun showLevel2SlotMenu(dateKey: String, dayName: String) {
        currentLevel = 2
        tvHeaderTitle.text = dayName
        contentContainer.removeAllViews()

        val dayJson = getDayJson(dateKey)
        val slotsObj = dayJson.optJSONObject("slots") ?: JSONObject()

        for (i in 1..5) {
            val slotData = slotsObj.optJSONObject(i.toString())
            val pSec = slotData?.optLong("presentSec", 0L) ?: 0L
            val aSec = slotData?.optLong("absentSec", 0L) ?: 0L

            val card = CardView(this).apply {
                radius = 20f
                setCardBackgroundColor(Color.parseColor("#1E293B"))
                cardElevation = 4f
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 14)
                layoutParams = params
            }

            val cardContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
            }

            val tvSlotTitle = TextView(this).apply {
                text = "📘 Slot $i Overview"
                textSize = 15f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, Typeface.BOLD)
            }

            val tvStats = TextView(this).apply {
                text = "🟢 Present: ${formatDuration(pSec)}  |  🔴 Absent: ${formatDuration(aSec)}"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(0, 6, 0, 0)
            }

            cardContent.addView(tvSlotTitle)
            cardContent.addView(tvStats)
            card.addView(cardContent)

            card.setOnClickListener {
                showLevel3SlotDetail(dateKey, dayName, i)
            }

            contentContainer.addView(card)
        }

        // ALL-DAY CONSOLIDATED REPORT BUTTON
        val btnAllDay = Button(this).apply {
            text = "🌟 📊 View All-Day Consolidated Report"
            textSize = 14f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#F59E0B"))
            setTypeface(null, Typeface.BOLD)
            setAllCaps(false)
            setPadding(0, 24, 0, 24)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 10, 0, 20)
            layoutParams = params
            setOnClickListener {
                showLevel3AllDaySummary(dateKey, dayName)
            }
        }
        contentContainer.addView(btnAllDay)
    }

    /**
     * LEVEL 3: Detailed Data Log for Specific Slot
     */
    private fun showLevel3SlotDetail(dateKey: String, dayName: String, slotNum: Int) {
        currentLevel = 3
        tvHeaderTitle.text = "Slot $slotNum Details"
        contentContainer.removeAllViews()

        val dayJson = getDayJson(dateKey)
        val slotsObj = dayJson.optJSONObject("slots") ?: JSONObject()
        val slotData = slotsObj.optJSONObject(slotNum.toString())

        val pSec = slotData?.optLong("presentSec", 0L) ?: 0L
        val aSec = slotData?.optLong("absentSec", 0L) ?: 0L
        val absences = slotData?.optJSONArray("absences") ?: JSONArray()

        val summaryCard = CardView(this).apply {
            radius = 24f
            setCardBackgroundColor(Color.parseColor("#1E293B"))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 18)
            layoutParams = params
        }
        val sumLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val tvPres = TextView(this).apply {
            text = "🟢 Total Study (Present): ${formatDuration(pSec)}"
            textSize = 15f
            setTextColor(Color.parseColor("#22C55E"))
            setTypeface(null, Typeface.BOLD)
        }
        val tvAbs = TextView(this).apply {
            text = "🔴 Total Away (Absent): ${formatDuration(aSec)}"
            textSize = 15f
            setTextColor(Color.parseColor("#EF4444"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 0)
        }
        sumLayout.addView(tvPres)
        sumLayout.addView(tvAbs)
        summaryCard.addView(sumLayout)
        contentContainer.addView(summaryCard)

        val tvIntervalHeader = TextView(this).apply {
            text = "⏱ Logged Absent Intervals (Alarm Rings):"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 6, 0, 10)
        }
        contentContainer.addView(tvIntervalHeader)

        if (absences.length() == 0) {
            val tvEmpty = TextView(this).apply {
                text = "🎉 Excellent discipline! No unexcused absences recorded for this slot."
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, 12, 0, 0)
            }
            contentContainer.addView(tvEmpty)
        } else {
            for (j in 0 until absences.length()) {
                val item = absences.getJSONObject(j)
                val start = item.optString("start")
                val end = item.optString("end")
                val dur = item.optLong("durationSec")

                val cardItem = CardView(this).apply {
                    radius = 16f
                    setCardBackgroundColor(Color.parseColor("#1E293B"))
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.setMargins(0, 0, 0, 10)
                    layoutParams = params
                }
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 16, 20, 16)
                }
                val tvRange = TextView(this).apply {
                    text = "${j + 1}. $start  ➔  $end"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }
                val tvDur = TextView(this).apply {
                    text = "Away Duration: ${formatDuration(dur)}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#EF4444"))
                    setPadding(0, 4, 0, 0)
                }
                itemLayout.addView(tvRange)
                itemLayout.addView(tvDur)
                cardItem.addView(itemLayout)
                contentContainer.addView(cardItem)
            }
        }
    }

    /**
     * LEVEL 3: Full-Day Consolidated Report
     */
    private fun showLevel3AllDaySummary(dateKey: String, dayName: String) {
        currentLevel = 3
        tvHeaderTitle.text = "All-Day Full Report"
        contentContainer.removeAllViews()

        val dayJson = getDayJson(dateKey)
        val slotsObj = dayJson.optJSONObject("slots") ?: JSONObject()

        var grandPresent = 0L
        var grandAbsent = 0L

        for (i in 1..5) {
            val slotData = slotsObj.optJSONObject(i.toString())
            grandPresent += slotData?.optLong("presentSec", 0L) ?: 0L
            grandAbsent += slotData?.optLong("absentSec", 0L) ?: 0L
        }

        val grandCard = CardView(this).apply {
            radius = 24f
            setCardBackgroundColor(Color.parseColor("#0F766E"))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 18)
            layoutParams = params
        }
        val grandLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val tvGPres = TextView(this).apply {
            text = "🏆 Total Study Time: ${formatDuration(grandPresent)}"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        val tvGAbs = TextView(this).apply {
            text = "⚠ Total Absent Time: ${formatDuration(grandAbsent)}"
            textSize = 14f
            setTextColor(Color.parseColor("#FEF08A"))
            setPadding(0, 6, 0, 0)
        }
        grandLayout.addView(tvGPres)
        grandLayout.addView(tvGAbs)
        grandCard.addView(grandLayout)
        contentContainer.addView(grandCard)

        for (i in 1..5) {
            val slotData = slotsObj.optJSONObject(i.toString())
            val pSec = slotData?.optLong("presentSec", 0L) ?: 0L
            val aSec = slotData?.optLong("absentSec", 0L) ?: 0L
            val absences = slotData?.optJSONArray("absences") ?: JSONArray()

            val card = CardView(this).apply {
                radius = 20f
                setCardBackgroundColor(Color.parseColor("#1E293B"))
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 14)
                layoutParams = params
            }
            val lay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 18, 20, 18)
            }
            val tvTitle = TextView(this).apply {
                text = "📘 Slot $i Summary"
                textSize = 14f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, Typeface.BOLD)
            }
            val tvBody = TextView(this).apply {
                text = "Present: ${formatDuration(pSec)}  |  Absent: ${formatDuration(aSec)}"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(0, 4, 0, 0)
            }
            lay.addView(tvTitle)
            lay.addView(tvBody)

            if (absences.length() > 0) {
                for (j in 0 until absences.length()) {
                    val item = absences.getJSONObject(j)
                    val tvInt = TextView(this).apply {
                        text = "  • ${item.optString("start")} ➔ ${item.optString("end")} (${formatDuration(item.optLong("durationSec"))})"
                        textSize = 11f
                        setTextColor(Color.parseColor("#EF4444"))
                    }
                    lay.addView(tvInt)
                }
            }
            card.addView(lay)
            contentContainer.addView(card)
        }
    }
}
