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

class Key(val id: String, val label: String, val code: Long, val color: String?)
class Cell(val k: String?, val s: String, val t: String?)
class Layout(val name: String, val keys: List<Key>, val rows: List<List<Cell>>?)
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
    private lateinit var flip: CheckBox
    private val prefs by lazy { getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null
    private val codes = HashMap<String, Long>()
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

        flip = CheckBox(this)
        flip.text = "Flip bit order (use only if keys do nothing)"
        flip.setOnCheckedChangeListener { _, _ ->
            if (!updating) {
                prefs.edit().putBoolean("flip_" + receivers[currentReceiver].name, flip.isChecked).apply()
            }
        }
        root.addView(flip)

        var tryFlip = false
        val findStatus = TextView(this)
        val findBtn = Button(this)
        findBtn.text = "Test: send Power (tap again to flip bit order)"
        findBtn.isAllCaps = false
        findBtn.setOnClickListener {
            val r = receivers[currentReceiver]
            val lay = layouts[r.layoutIds[layoutSpinner.selectedItemPosition.coerceAtLeast(0)]]
            val power = lay.keys.firstOrNull { it.label == "Power" } ?: lay.keys[0]
            flip.isChecked = tryFlip
            send(power.code)
            findStatus.text = "Sent Power with flip bit order " + (if (tryFlip) "ON" else "off") +
                ". If the receiver reacted, stop: this setting is saved for this receiver."
            tryFlip = !tryFlip
        }
        root.addView(findBtn)
        root.addView(findStatus)

        holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        root.addView(holder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        scroll.addView(root)
        scroll.clipToPadding = false
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

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
        flip.isChecked = prefs.getBoolean("flip_" + r.name, false)
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
        if (layout.name.startsWith("Octagon SF8008")) buildOctagon(layout)
        else if (layout.rows != null) buildRows(layout)
        else buildGrid(layout)
    }

    private fun buildGrid(layout: Layout) {
        val colorMap = mapOf("Red" to "#E53935", "Green" to "#43A047", "Yellow" to "#FDD835", "Blue" to "#1E88E5")
        val grid = GridLayout(this)
        grid.columnCount = 4
        for (k in layout.keys) {
            val b = Button(this)
            b.text = k.label
            b.isAllCaps = false
            b.textSize = 12f
            b.minWidth = 0
            b.minimumWidth = 0
            b.setOnClickListener { send(k.code) }
            val col = k.color
            val hex = if (col != null) colorMap[col] else if (k.label == "Power") "#8E24AA" else null
            if (hex != null) {
                val d = GradientDrawable()
                d.setColor(Color.parseColor(hex))
                d.cornerRadius = dp(10).toFloat()
                b.background = d
                b.setTextColor(Color.parseColor(if (col == "Yellow") "#111111" else "#FFFFFF"))
                if (k.label.isEmpty() && col != null) b.contentDescription = col
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            grid.addView(b, lp)
        }
        holder.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun attachRepeat(b: Button, code: Long) {
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
    }

    private fun buildRows(layout: Layout) {
        val byId = HashMap<String, Key>()
        for (k in layout.keys) byId[k.id] = k
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(8), dp(14), dp(8), dp(14))
        val bd = GradientDrawable()
        bd.setColor(Color.parseColor("#1B1B1B"))
        bd.cornerRadius = dp(28).toFloat()
        body.background = bd
        for (r in layout.rows ?: emptyList()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in r) {
                val kid = c.k
                val key = if (kid != null) byId[kid] else null
                if (key == null) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, dp(8), 1f))
                    continue
                }
                val text = c.t ?: key.label
                val bg: String
                val fg: String
                when (c.s) {
                    "p" -> { bg = "#8E24AA"; fg = "#FFFFFF" }
                    "r" -> { bg = "#E53935"; fg = "#FFFFFF" }
                    "g" -> { bg = "#43A047"; fg = "#FFFFFF" }
                    "y" -> { bg = "#FDD835"; fg = "#111111" }
                    "b" -> { bg = "#1E88E5"; fg = "#FFFFFF" }
                    "d" -> { bg = "#A9A9A9"; fg = "#111111" }
                    "w" -> { bg = "#E0E0E0"; fg = "#111111" }
                    else -> { bg = "#2E2E2E"; fg = "#FFFFFF" }
                }
                val b = Button(this)
                b.text = text
                b.isAllCaps = false
                b.setTextColor(Color.parseColor(fg))
                b.textSize = if (text.length > 6) 10f else 13f
                b.minWidth = 0
                b.minHeight = 0
                b.minimumWidth = 0
                b.minimumHeight = 0
                b.setPadding(dp(2), 0, dp(2), 0)
                b.maxLines = 1
                val d = GradientDrawable()
                d.setColor(Color.parseColor(bg))
                d.cornerRadius = dp(if (c.s == "d" || c.s == "o") 22 else 10).toFloat()
                b.background = d
                val lp = LinearLayout.LayoutParams(0, dp(if (c.s == "o") 54 else 44), 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(b, lp)
                val code = key.code
                if (c.s == "a") attachRepeat(b, code) else b.setOnClickListener { send(code) }
            }
            body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        holder.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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

    private fun send(code: Long) {
        val irm = ir
        if (irm == null || !irm.hasIrEmitter()) {
            Toast.makeText(this, "No IR blaster found", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = intArrayOf(((code shr 24) and 0xFF).toInt(), ((code shr 16) and 0xFF).toInt(),
            ((code shr 8) and 0xFF).toInt(), (code and 0xFF).toInt())
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        for (b in bytes) {
            for (i in 0 until 8) {
                val bit = if (flip.isChecked) (b shr i) and 1 else (b shr (7 - i)) and 1
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
                keys.add(Key(k.getString("id"), k.getString("label"), k.getLong("code"), if (k.has("color")) k.getString("color") else null))
            }
            var rows: ArrayList<List<Cell>>? = null
            if (o.has("rows")) {
                rows = ArrayList()
                val rs0 = o.getJSONArray("rows")
                for (a in 0 until rs0.length()) {
                    val ra = rs0.getJSONArray(a)
                    val cells = ArrayList<Cell>()
                    for (bI in 0 until ra.length()) {
                        val c = ra.getJSONObject(bI)
                        cells.add(Cell(if (c.isNull("k")) null else c.getString("k"), c.getString("s"),
                            if (c.has("t")) c.getString("t") else null))
                    }
                    rows.add(cells)
                }
            }
            layouts.add(Layout(o.getString("name"), keys, rows))
        }
        val rs = json.getJSONArray("receivers")
        for (i in 0 until rs.length()) {
            val o = rs.getJSONObject(i)
            val ids = o.getJSONArray("layouts")
            receivers.add(Receiver(o.getString("name"), (0 until ids.length()).map { ids.getInt(it) }))
        }
    }
}
