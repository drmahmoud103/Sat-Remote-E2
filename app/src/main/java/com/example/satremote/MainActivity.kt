package com.example.satremote

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class Key(val id: String, val label: String, val code: Int)
class Layout(val name: String, val keys: List<Key>)
class Receiver(val name: String, val layoutIds: List<Int>)

class MainActivity : Activity() {
    private var ir: ConsumerIrManager? = null
    private val layouts = ArrayList<Layout>()
    private val receivers = ArrayList<Receiver>()
    private lateinit var recSpinner: Spinner
    private lateinit var layoutSpinner: Spinner
    private lateinit var layoutLabel: TextView
    private lateinit var showLayouts: CheckBox
    private lateinit var holder: LinearLayout
    private lateinit var invHi: CheckBox
    private lateinit var invLo: CheckBox
    private lateinit var msb: CheckBox
    private val prefs by lazy { getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null
    private val codes = HashMap<String, Int>()
    private var currentReceiver = 0
    private var updating = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        loadData()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val status = TextView(this)
        status.text = if (ir?.hasIrEmitter() == true) "IR blaster ready" else "No IR blaster found on this phone"
        root.addView(status)

        root.addView(label("Receiver"))
        recSpinner = Spinner(this)
        recSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, receivers.map { it.name })
        root.addView(recSpinner)

        showLayouts = CheckBox(this)
        showLayouts.text = "Show other remote layouts"
        showLayouts.setOnCheckedChangeListener { _, _ -> updateLayoutVisibility() }
        root.addView(showLayouts)
        layoutLabel = label("Remote layout (try another if keys do nothing)")
        root.addView(layoutLabel)
        layoutSpinner = Spinner(this)
        root.addView(layoutSpinner)

        root.addView(label("Signal options (change if the receiver does not react)"))
        invHi = CheckBox(this).apply { text = "Invert first byte"; isChecked = false }
        invLo = CheckBox(this).apply { text = "Invert second byte"; isChecked = false }
        msb = CheckBox(this).apply { text = "MSB first"; isChecked = false }
        for (c in listOf(invHi, invLo, msb)) {
            c.setOnCheckedChangeListener { _, _ ->
                if (!updating) {
                    val n = receivers[currentReceiver].name
                    prefs.edit().putBoolean("invHi_" + n, invHi.isChecked).putBoolean("invLo_" + n, invLo.isChecked)
                        .putBoolean("msb_" + n, msb.isChecked).apply()
                }
            }
            root.addView(c)
        }

        val order = intArrayOf(1, 3, 5, 7, 0, 2, 4, 6)
        var tryIndex = 0
        val findStatus = TextView(this)
        val findBtn = Button(this)
        findBtn.text = "Find signal: sends Power, tap again for next try"
        findBtn.isAllCaps = false
        findBtn.setOnClickListener {
            val r = receivers[currentReceiver]
            val lay = layouts[r.layoutIds[layoutSpinner.selectedItemPosition.coerceAtLeast(0)]]
            val power = lay.keys.firstOrNull { it.label == "Power" } ?: lay.keys[0]
            val c = order[tryIndex]
            invHi.isChecked = (c and 1) != 0
            invLo.isChecked = (c and 2) != 0
            msb.isChecked = (c and 4) != 0
            send(power.code)
            findStatus.text = "Sent Power, try " + (tryIndex + 1) + " of 8. Invert first byte: " +
                (if (invHi.isChecked) "ON" else "off") + ", invert second byte: " +
                (if (invLo.isChecked) "ON" else "off") + ", MSB first: " +
                (if (msb.isChecked) "ON" else "off") +
                ". If the receiver reacted, stop: these settings are saved for this receiver."
            tryIndex = (tryIndex + 1) % 8
        }
        root.addView(findBtn)
        root.addView(findStatus)

        holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        root.addView(holder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply { addView(root) })

        recSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                currentReceiver = pos
                prefs.edit().putString("receiver", receivers[pos].name).apply()
                fillLayouts()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                prefs.edit().putInt("layout_" + receivers[currentReceiver].name, pos).apply()
                buildKeys(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val saved = prefs.getString("receiver", "Octagon SF8008")
        currentReceiver = receivers.indexOfFirst { it.name == saved }.coerceAtLeast(0)
        updating = true
        recSpinner.setSelection(currentReceiver)
        updating = false
        fillLayouts()
    }

    override fun onPause() {
        super.onPause()
        stopRepeat()
    }

    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, dp(2)) }

    private fun fillLayouts() {
        val r = receivers[currentReceiver]
        updating = true
        invHi.isChecked = prefs.getBoolean("invHi_" + r.name, false)
        invLo.isChecked = prefs.getBoolean("invLo_" + r.name, false)
        msb.isChecked = prefs.getBoolean("msb_" + r.name, false)
        layoutSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            r.layoutIds.map { layouts[it].name })
        val pos = prefs.getInt("layout_" + r.name, 0).coerceIn(0, r.layoutIds.size - 1)
        layoutSpinner.setSelection(pos)
        updating = false
        updateLayoutVisibility()
        buildKeys(pos)
    }

    private fun updateLayoutVisibility() {
        val many = receivers[currentReceiver].layoutIds.size > 1
        showLayouts.visibility = if (many) View.VISIBLE else View.GONE
        val show = many && showLayouts.isChecked
        layoutLabel.visibility = if (show) View.VISIBLE else View.GONE
        layoutSpinner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun buildKeys(pos: Int) {
        val layout = layouts[receivers[currentReceiver].layoutIds[pos]]
        holder.removeAllViews()
        stopRepeat()
        if (layout.name.startsWith("Octagon SF8008")) buildOctagon(layout) else buildGrid(layout)
    }

    private fun buildGrid(layout: Layout) {
        val gridColors = mapOf("Red" to "#E53935", "Green" to "#43A047", "Yellow" to "#FDD835",
            "Blue" to "#1E88E5", "Power" to "#8E24AA")
        val grid = GridLayout(this)
        grid.columnCount = 4
        for (k in layout.keys) {
            val b = Button(this)
            b.text = k.label
            b.isAllCaps = false
            b.setOnClickListener { send(k.code) }
            val keyColor = gridColors[k.label]
            if (keyColor != null) {
                val d = GradientDrawable()
                d.setColor(Color.parseColor(keyColor))
                d.cornerRadius = dp(10).toFloat()
                b.background = d
                b.setTextColor(Color.parseColor(if (k.label == "Yellow") "#111111" else "#FFFFFF"))
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            grid.addView(b, lp)
        }
        holder.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun stopRepeat() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER
        parent.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return r
    }

    private fun column(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        parent.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return c
    }

    private fun key(parent: LinearLayout, id: String, text: String, bg: String, fg: String,
                    oval: Boolean, w: Int, h: Int, size: Float = 14f, repeat: Boolean = false) {
        val code = codes[id] ?: return
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.parseColor(fg))
        b.textSize = size
        b.minWidth = 0
        b.minHeight = 0
        b.minimumWidth = 0
        b.minimumHeight = 0
        b.setPadding(0, 0, 0, 0)
        val d = GradientDrawable()
        d.shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        d.setColor(Color.parseColor(bg))
        if (!oval) d.cornerRadius = dp(10).toFloat()
        b.background = d
        val lp = LinearLayout.LayoutParams(dp(w), dp(h))
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        parent.addView(b, lp)
        if (repeat) {
            b.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        send(code)
                        stopRepeat()
                        val r = object : Runnable {
                            override fun run() {
                                send(code)
                                handler.postDelayed(this, 160)
                            }
                        }
                        repeater = r
                        handler.postDelayed(r, 500)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        stopRepeat()
                    }
                }
                true
            }
        } else {
            b.setOnClickListener { send(code) }
        }
    }

    private fun buildOctagon(layout: Layout) {
        codes.clear()
        for (k in layout.keys) codes[k.id] = k.code
        val red = "#E53935"; val green = "#43A047"; val yellow = "#FDD835"; val blue = "#1E88E5"; val powerColor = "#8E24AA"
        val digit = "#A9A9A9"; val dark = "#2E2E2E"; val light = "#C9C9C9"
        val white = "#FFFFFF"; val black = "#111111"

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(dp(8), dp(16), dp(8), dp(16))
        val bd = GradientDrawable()
        bd.setColor(Color.parseColor("#1B1B1B"))
        bd.cornerRadius = dp(32).toFloat()
        body.background = bd

        key(row(body), "K_POWER", "Power", powerColor, white, true, 56, 56, 12f)

        val nums = listOf(listOf("K_1", "K_2", "K_3"), listOf("K_4", "K_5", "K_6"), listOf("K_7", "K_8", "K_9"))
        for (r in nums) {
            val rw = row(body)
            for (id in r) key(rw, id, id.removePrefix("K_"), digit, black, true, 56, 56, 20f)
        }
        val last = row(body)
        key(last, "K_PREVIOUS", "<", digit, black, true, 56, 56, 20f)
        key(last, "K_0", "0", digit, black, true, 56, 56, 20f)
        key(last, "K_NEXT", ">", digit, black, true, 56, 56, 20f)

        val f = row(body)
        for (n in 1..4) key(f, "K_F" + n, "F" + n, dark, white, false, 62, 38)

        val m = row(body)
        key(m, "K_MUTE", "Mute", dark, white, false, 62, 38, 12f)
        key(m, "K_MODE", "Resolution", dark, white, false, 62, 38, 10f)
        key(m, "K_HELP", "Help", dark, white, false, 62, 38, 12f)
        key(m, "K_OPTION", "Magic", dark, white, false, 62, 38, 12f)

        val r1 = row(body)
        key(r1, "K_MENU", "Menu", dark, white, true, 52, 52, 11f)
        key(r1, "K_UP", "▲", dark, white, false, 68, 48, 16f, true)
        key(r1, "K_EXIT", "Exit", dark, white, true, 52, 52, 11f)
        val r2 = row(body)
        key(r2, "K_LEFT", "◀", dark, white, false, 52, 64, 16f, true)
        key(r2, "K_OK", "OK", dark, white, true, 68, 68, 18f)
        key(r2, "K_RIGHT", "▶", dark, white, false, 52, 64, 16f, true)
        val r3 = row(body)
        key(r3, "K_INFO", "Info", dark, white, true, 52, 52, 11f)
        key(r3, "K_DOWN", "▼", dark, white, false, 68, 48, 16f, true)
        key(r3, "K_BACK", "Recall", dark, white, true, 52, 52, 10f)

        val vc = row(body)
        val cv = column(vc)
        key(cv, "K_VOLUMEUP", "Vol +", light, black, false, 56, 56, 12f, true)
        key(cv, "K_VOLUMEDOWN", "Vol -", light, black, false, 56, 56, 12f, true)
        val cm = column(vc)
        key(cm, "K_VIDEO", "Playlist", dark, white, false, 72, 44, 12f)
        key(cm, "K_EPG", "EPG", dark, white, false, 72, 44, 12f)
        val cc = column(vc)
        key(cc, "K_CHANNELUP", "Ch ▲", light, black, false, 56, 56, 12f, true)
        key(cc, "K_CHANNELDOWN", "Ch ▼", light, black, false, 56, 56, 12f, true)

        val col = row(body)
        key(col, "K_RED", "", red, white, false, 62, 40)
        key(col, "K_GREEN", "", green, white, false, 62, 40)
        key(col, "K_YELLOW", "", yellow, black, false, 62, 40)
        key(col, "K_BLUE", "", blue, white, false, 62, 40)

        val t1 = row(body)
        key(t1, "K_REWIND", "◀◀", dark, white, false, 62, 38)
        key(t1, "K_PLAY", "▶", dark, white, false, 62, 38)
        key(t1, "K_PAUSE", "❚❚", dark, white, false, 62, 38)
        key(t1, "K_FASTFORWARD", "▶▶", dark, white, false, 62, 38)

        val t2 = row(body)
        key(t2, "K_AUDIO", "Audio", dark, white, false, 62, 38, 12f)
        key(t2, "K_STOP", "■", dark, white, false, 62, 38)
        key(t2, "K_RECORD", "●", dark, "#E53935", false, 62, 38)
        key(t2, "K_PLAYER", "Context", dark, white, false, 62, 38, 12f)

        val t3 = row(body)
        key(t3, "K_TEXT", "TXT", dark, white, false, 62, 38, 12f)
        key(t3, "K_SUBTITLE", "SUBT", dark, white, false, 62, 38, 12f)
        key(t3, "K_TV", "TV/Radio", dark, white, false, 62, 38, 10f)
        key(t3, "K_FAVORITES", "FAV", dark, white, false, 62, 38, 12f)

        val brand = TextView(this)
        brand.text = "OCTAGON  SF8008"
        brand.setTextColor(Color.parseColor("#DDDDDD"))
        brand.textSize = 16f
        brand.gravity = Gravity.CENTER
        brand.setPadding(0, dp(14), 0, 0)
        body.addView(brand)

        holder.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun send(code: Int) {
        val irm = ir
        if (irm == null || !irm.hasIrEmitter()) {
            Toast.makeText(this, "No IR blaster found", Toast.LENGTH_SHORT).show()
            return
        }
        var hi = (code shr 8) and 0xFF
        var lo = code and 0xFF
        if (invHi.isChecked) hi = hi.inv() and 0xFF
        if (invLo.isChecked) lo = lo.inv() and 0xFF
        val bytes = intArrayOf(hi, hi.inv() and 0xFF, lo, lo.inv() and 0xFF)
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        for (b in bytes) {
            for (i in 0 until 8) {
                val bit = if (msb.isChecked) (b shr (7 - i)) and 1 else (b shr i) and 1
                p.add(560)
                p.add(if (bit == 1) 1690 else 560)
            }
        }
        p.add(560); p.add(40000)
        irm.transmit(38000, p.toIntArray())
    }

    private fun loadData() {
        val json = JSONObject(assets.open("remotes.json").bufferedReader().use { it.readText() })
        val ls = json.getJSONArray("layouts")
        for (i in 0 until ls.length()) {
            val o = ls.getJSONObject(i)
            val ks = o.getJSONArray("keys")
            val keys = ArrayList<Key>()
            for (j in 0 until ks.length()) {
                val k = ks.getJSONObject(j)
                keys.add(Key(k.getString("id"), k.getString("label"), k.getInt("code")))
            }
            layouts.add(Layout(o.getString("name"), keys))
        }
        val rs = json.getJSONArray("receivers")
        for (i in 0 until rs.length()) {
            val o = rs.getJSONObject(i)
            val ids = o.getJSONArray("layouts")
            receivers.add(Receiver(o.getString("name"), (0 until ids.length()).map { ids.getInt(it) }))
        }
    }
}
