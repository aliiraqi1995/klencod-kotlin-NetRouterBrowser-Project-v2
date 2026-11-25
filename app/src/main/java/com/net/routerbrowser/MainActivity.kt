package com.net.routerbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var etUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var credentialTester: CredentialTesterHelper

    private val tabs = mutableListOf<WebView>()
    private var activeTabIndex = -1
    private var isDesktopMode = false
    private var pickingForUserField = false

    private val wordlistPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        if (pickingForUserField) {
            credentialTester.onUserWordlistPicked(uri)
        } else {
            credentialTester.onPassWordlistPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        createNewTab("http://192.168.1.1")
    }

    private fun initViews() {
        webContainer = findViewById(R.id.webContainer)
        etUrl = findViewById(R.id.etUrl)
        progressBar = findViewById(R.id.progressBar)

        credentialTester = CredentialTesterHelper(
            context = this,
            getActiveWebView = { getCurrentWebView() },
            onPickUserWordlist = {
                pickingForUserField = true
                wordlistPickerLauncher.launch(arrayOf("text/plain", "*/*"))
            },
            onPickPassWordlist = {
                pickingForUserField = false
                wordlistPickerLauncher.launch(arrayOf("text/plain", "*/*"))
            }
        )

        credentialTester.attachStatusBar(
            container = findViewById(R.id.testerStatusBar),
            statusText = findViewById(R.id.tvTesterStatus),
            btnStop = findViewById(R.id.btnStopTester)
        )

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            val wv = getCurrentWebView()
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else if (tabs.size > 1) {
                closeCurrentTab()
            }
        }

        findViewById<Button>(R.id.btnGo).setOnClickListener {
            loadInputUrl()
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadInputUrl()
                true
            } else {
                false
            }
        }

        findViewById<ImageButton>(R.id.btnNewTab).setOnClickListener {
            createNewTab("http://192.168.1.1")
        }

        findViewById<ImageButton>(R.id.btnDesktop).setOnClickListener {
            toggleDesktopMode()
        }

        findViewById<ImageButton>(R.id.btnHistory).setOnClickListener {
            showHistoryDialog()
        }

        findViewById<ImageButton>(R.id.btnWordlist).setOnClickListener {
            credentialTester.showConfigDialog()
        }

        findViewById<ImageButton>(R.id.btnSavedCreds).setOnClickListener {
            credentialTester.showSavedCredentialsDialog()
        }
    }

    private fun loadInputUrl() {
        var raw = etUrl.text.toString().trim()
        if (raw.isEmpty()) return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://$raw"
        }
        getCurrentWebView()?.loadUrl(raw)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(url: String) {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            applyUserAgent(this, isDesktopMode)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (!loadedUrl.isNullOrEmpty() && view == getCurrentWebView()) {
                        etUrl.setText(loadedUrl)
                        saveHistoryItem(loadedUrl)
                    }
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view == getCurrentWebView()) {
                        if (newProgress in 1..99) {
                            progressBar.visibility = View.VISIBLE
                            progressBar.progress = newProgress
                        } else {
                            progressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }

        tabs.add(webView)
        switchToTab(tabs.lastIndex)
        webView.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        webContainer.removeAllViews()
        val current = tabs[activeTabIndex]
        webContainer.addView(current)
        etUrl.setText(current.url ?: "")
    }

    private fun closeCurrentTab() {
        if (activeTabIndex !in tabs.indices) return
        val toRemove = tabs.removeAt(activeTabIndex)
        webContainer.removeView(toRemove)
        toRemove.destroy()
        if (tabs.isEmpty()) {
            createNewTab("http://192.168.1.1")
        } else {
            switchToTab((activeTabIndex - 1).coerceAtLeast(0))
        }
    }

    private fun getCurrentWebView(): WebView? {
        return if (activeTabIndex in tabs.indices) tabs[activeTabIndex] else null
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        tabs.forEach { applyUserAgent(it, isDesktopMode) }
        getCurrentWebView()?.reload()
    }

    private fun applyUserAgent(webView: WebView, desktop: Boolean) {
        val defaultUa = WebSettings.getDefaultUserAgent(this)
        webView.settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        } else {
            defaultUa
        }
    }

    private fun saveHistoryItem(url: String) {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("history_list", "[]") ?: "[]")
        val updated = JSONArray()
        updated.put(url)
        for (i in 0 until arr.length()) {
            val item = arr.optString(i)
            if (item != url && updated.length() < 50) {
                updated.put(item)
            }
        }
        prefs.edit().putString("history_list", updated.toString()).apply()
    }

    private fun showHistoryDialog() {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("history_list", "[]") ?: "[]")
        if (arr.length() == 0) return

        val items = Array(arr.length()) { idx -> arr.optString(idx) }
        AlertDialog.Builder(this)
            .setTitle("History")
            .setItems(items) { _, which ->
                val target = items[which]
                etUrl.setText(target)
                getCurrentWebView()?.loadUrl(target)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        credentialTester.stopTesting(silent = true)
        tabs.forEach { it.destroy() }
        super.onDestroy()
    }
}