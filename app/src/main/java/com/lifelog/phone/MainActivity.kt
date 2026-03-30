package com.lifelog.phone

import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private var webView: WebView? = null
    private val REQ_LOCATION = 5179
    private val REQ_MIC = 5180
    private val REQ_WIFI = 5181

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Handle ADB provisioning via intent extras
        handleAdbExtras()

        // Request permissions and auto-start the foreground service
        ensurePermissions()
        startLifeLogService()
        ensureUsageStatsPermission()

        // Show setup screen on first run, WebView otherwise
        if (prefs.isSetupComplete()) {
            showWebView()
        } else {
            showSetup()
        }
    }

    private fun handleAdbExtras() {
        val extraBase = intent?.getStringExtra("base_url")?.trim().orEmpty()
        val extraTok = intent?.getStringExtra("token")?.trim().orEmpty()
        val extraDev = intent?.getStringExtra("device_id")?.trim().orEmpty()
        val extraPv = intent?.getStringExtra("picovoice_access_key")?.trim().orEmpty()
        val extraWake = intent?.getStringExtra("wakeword_enabled")?.trim().orEmpty()
        val extraLog = intent?.getStringExtra("logger_enabled")?.trim().orEmpty()
        if (extraBase.isNotEmpty() || extraTok.isNotEmpty() || extraDev.isNotEmpty() ||
            extraPv.isNotEmpty() || extraWake.isNotEmpty() || extraLog.isNotEmpty()) {
            val b = if (extraBase.isNotEmpty()) extraBase else prefs.baseUrl()
            val t = if (extraTok.isNotEmpty()) extraTok else prefs.token()
            val d = if (extraDev.isNotEmpty()) extraDev else prefs.deviceId()
            val p = if (extraPv.isNotEmpty()) extraPv else prefs.picovoiceAccessKey()
            val w = if (extraWake.isNotEmpty()) (extraWake == "1" || extraWake.equals("true", true)) else prefs.wakewordEnabled()
            val lg = if (extraLog.isNotEmpty()) (extraLog == "1" || extraLog.equals("true", true)) else prefs.loggerEnabled()
            prefs.save(b, t, d, p, w, lg)
            prefs.setSetupComplete(true)
        }
    }

    private fun showSetup() {
        setContentView(R.layout.activity_setup)
        val baseUrl = findViewById<EditText>(R.id.setupBaseUrl)
        val token = findViewById<EditText>(R.id.setupToken)
        val status = findViewById<TextView>(R.id.setupStatus)
        val connect = findViewById<Button>(R.id.setupConnect)

        // Pre-fill existing values
        baseUrl.setText(prefs.baseUrl())
        token.setText(prefs.token())

        connect.setOnClickListener {
            val url = baseUrl.text.toString().trim()
            val tok = token.text.toString().trim()
            if (url.isEmpty()) {
                status.text = "Please enter a server URL"
                return@setOnClickListener
            }
            status.text = "Connecting..."
            connect.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Save prefs first so the service picks them up
                    prefs.save(url, tok, prefs.deviceId(), prefs.picovoiceAccessKey(),
                        prefs.wakewordEnabled(), prefs.loggerEnabled())

                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder()
                        .url("${url.trimEnd('/')}/health")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            prefs.setSetupComplete(true)
                            withContext(Dispatchers.Main) {
                                showWebView()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                status.text = "Server returned ${resp.code}. Check URL."
                                connect.isEnabled = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        status.text = "Connection failed: ${e.message?.take(80)}"
                        connect.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showWebView() {
        setContentView(R.layout.activity_main)
        webView = findViewById<WebView>(R.id.webview).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.databaseEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(prefs.dashboardUrl())
        }
        Log.i("MainActivity", "WebView loading: ${prefs.dashboardUrl()}")
    }

    private fun ensurePermissions() {
        ensureWifiPermission()
        ensureLocationPermission()
        ensureMicPermission()
    }

    private fun startLifeLogService() {
        try {
            startForegroundService(Intent(this, LifeLogService::class.java))
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to start service: ${e.message}")
        }
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_LOCATION
        )
    }

    private fun ensureMicPermission() {
        val mic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
        if (mic == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun ensureWifiPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val p = ContextCompat.checkSelfPermission(this, android.Manifest.permission.NEARBY_WIFI_DEVICES)
        if (p == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), REQ_WIFI)
    }

    private fun ensureUsageStatsPermission() {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        if (mode != AppOpsManager.MODE_ALLOWED) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) { }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
