package com.example.satremote

import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
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

class Key(val label: String, val code: Int)
class Layout(val name: String, val keys: List<Key>)
class Receiver(val name: String, val layoutIds: List<Int>)

class MainActivity : Activity() {
    private var ir: ConsumerIrManager? = null
    private val layouts = ArrayList<Layout>()
    private val receivers = ArrayList<Receiver>()
    private lateinit var recSpinner: Spinner
    private lateinit var layoutSpinner: Spinner
    private lateinit var grid: GridLayout
    private lateinit var invHi: CheckBox
    private lateinit var invLo: CheckBox
    private lateinit var msb: CheckBox
    private val prefs by lazy { getSharedPreferences("prefs", Context.MODE_PRIVATE) }
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

        root.addView(label("Remote layout (try another if keys do nothing)"))
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

        grid = GridLayout(this).apply { columnCount = 4 }
        root.addView(grid)
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
        buildKeys(pos)
    }

    private fun buildKeys(pos: Int) {
        val layout = layouts[receivers[currentReceiver].layoutIds[pos]]
        grid.removeAllViews()
        for (k in layout.keys) {
            val b = Button(this)
            b.text = k.label
            b.isAllCaps = false
            b.setOnClickListener { send(k.code) }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            grid.addView(b, lp)
        }
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
                keys.add(Key(k.getString("label"), k.getInt("code")))
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
