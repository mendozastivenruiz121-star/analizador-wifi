package com.example.wifianalizador

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class ChannelChart(c: Context) : View(c) {
    var load = FloatArray(14)
    var best = 1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / 13f
        val mx = (load.maxOrNull() ?: 1f).coerceAtLeast(1f)
        p.textSize = 28f
        p.textAlign = Paint.Align.CENTER
        for (ch in 1..13) {
            val bh = (h - 50f) * (load[ch] / mx)
            p.color = if (ch == best) Color.rgb(11, 122, 100) else Color.rgb(150, 170, 165)
            cv.drawRect(slot * (ch - 1) + 8f, h - 40f - bh, slot * ch - 8f, h - 40f, p)
            p.color = Color.DKGRAY
            cv.drawText("$ch", slot * (ch - 0.5f), h - 8f, p)
        }
    }
}

class MainActivity : Activity() {
    private lateinit var wifi: WifiManager
    private lateinit var current: TextView
    private lateinit var advice: TextView
    private lateinit var list: LinearLayout
    private lateinit var chart: ChannelChart
    private val ui = Handler(Looper.getMainLooper())
    private var results: List<ScanResult> = emptyList()

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = loadResults()
    }
    private val liveTick = object : Runnable {
        override fun run() { updateCurrent(); ui.postDelayed(this, 1000) }
    }
    private val autoScan = object : Runnable {
        override fun run() { scan(false); ui.postDelayed(this, 30000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hasPerm() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun chan(f: Int) = when {
        f == 2484 -> 14
        f < 3000 -> (f - 2407) / 5
        f < 5900 -> (f - 5000) / 5
        else -> (f - 5950) / 5
    }
    private fun band(f: Int) = if (f < 3000) "2.4 GHz" else if (f < 5900) "5 GHz" else "6 GHz"
    private fun security(cap: String) = when {
        cap.contains("WPA3") || cap.contains("SAE") -> "WPA3"
        cap.contains("WPA2") -> "WPA2"
        cap.contains("WPA") -> "WPA"
        cap.contains("WEP") -> "WEP"
        else -> "Abierta"
    }
    private fun quality(dbm: Int) = when {
        dbm >= -60 -> "Excelente" to Color.rgb(11, 122, 100)
        dbm >= -70 -> "Buena" to Color.rgb(60, 140, 60)
        dbm >= -80 -> "Débil" to Color.rgb(183, 121, 31)
        else -> "Muy débil" to Color.rgb(194, 37, 92)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Analizador WiFi"; textSize = 24f; setTypeface(null, Typeface.BOLD)
        })
        current = TextView(this).apply { textSize = 16f; setPadding(0, dp(10), 0, dp(10)) }
        root.addView(current)

        root.addView(Button(this).apply {
            text = "Escanear redes ahora"
            setOnClickListener { scan(true) }
        })

        root.addView(TextView(this).apply {
            text = "Saturación de canales (2.4 GHz)"; textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, dp(14), 0, dp(4))
        })
        chart = ChannelChart(this)
        root.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))

        advice = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(advice)

        root.addView(TextView(this).apply {
            text = "Redes cercanas"; textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, dp(4))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })
        advice.text = "Pulsa “Escanear redes ahora” para empezar."
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(rx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        ui.post(liveTick)
        ui.post(autoScan)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(rx)
        ui.removeCallbacksAndMessages(null)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasPerm()) scan(true)
        else advice.text = "Sin permiso de ubicación Android no deja ver las redes wifi. Actívalo en Ajustes de la app."
    }

    @Suppress("DEPRECATION")
    private fun scan(manual: Boolean) {
        if (!hasPerm()) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1); return }
        val ok = wifi.startScan()
        if (!ok && manual) Toast.makeText(this, "Android limita los escaneos seguidos. Mostrando el último resultado.", Toast.LENGTH_LONG).show()
        loadResults()
    }

    private fun loadResults() {
        if (!hasPerm()) return
        results = try { wifi.scanResults } catch (e: SecurityException) { emptyList() }
        render()
    }

    @Suppress("DEPRECATION")
    private fun updateCurrent() {
        val i = wifi.connectionInfo
        if (!hasPerm()) { current.text = "Falta el permiso de ubicación"; return }
        if (i == null || i.networkId == -1) { current.text = "No estás conectado a ningún wifi"; return }
        val (q, col) = quality(i.rssi)
        current.text = "${i.ssid.trim('"')}\n${i.rssi} dBm ($q) · ${i.linkSpeed} Mbps · ${band(i.frequency)} canal ${chan(i.frequency)}"
        current.setTextColor(col)
    }

    @Suppress("DEPRECATION")
    private fun render() {
        list.removeAllViews()
        val sorted = results.sortedByDescending { it.level }
        for (r in sorted) {
            val (q, col) = quality(r.level)
            val name = if (r.SSID.isNullOrEmpty()) "(red oculta)" else r.SSID
            list.addView(TextView(this).apply {
                text = "$name   ${r.level} dBm"; textSize = 16f
                setTypeface(null, Typeface.BOLD); setTextColor(col); setPadding(0, dp(10), 0, 0)
            })
            list.addView(TextView(this).apply {
                text = "$q · ${band(r.frequency)} · canal ${chan(r.frequency)} · ${security(r.capabilities)}"
                textSize = 13f; setTextColor(Color.GRAY)
            })
        }
        analyze()
    }

    private fun analyze() {
        val load = FloatArray(14)
        val r24 = results.filter { it.frequency < 3000 }
        for (r in r24) {
            val n = chan(r.frequency)
            val strength = ((r.level + 100) / 60f).coerceIn(0.05f, 1f)
            for (c in 1..13) {
                val d = abs(c - n)
                if (d <= 4) load[c] += (1f - d / 5f) * strength
            }
        }
        val best = listOf(1, 6, 11).minByOrNull { load[it] } ?: 1
        chart.load = load
        chart.best = best
        chart.invalidate()
        val n5 = results.size - r24.size
        advice.text = if (results.isEmpty()) "No se encontraron redes. Revisa que el wifi y la ubicación del celular estén activados."
        else "Redes cercanas: ${r24.size} en 2.4 GHz y $n5 en 5 GHz.\n" +
            "Canal recomendado para tu router en 2.4 GHz: $best (barra verde).\n" +
            "Si estás cerca del router, conéctate a la banda de 5 GHz: es más estable."
    }
}
