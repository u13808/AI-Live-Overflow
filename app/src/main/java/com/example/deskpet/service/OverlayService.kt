package com.example.deskpet.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * AI-Live-Overflow 核心悬浮窗服务
 *
 * 架构：大脑（AI）↔ Supabase ↔ 身体（本服务）
 * 本服务 = 悬浮窗 WebView + 传感器 + 手势识别
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 180
        private const val PET_HEIGHT_DP = 240

        // Supabase 配置（通过 SharedPreferences 或 BuildConfig 注入）
        var SUPABASE_URL = ""
        var SUPABASE_KEY = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🐾 我趴在屏幕上了~"))
        setupOverlay()
        startSensors()
    }

    // ==================== 悬浮窗设置 ====================

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            webViewClient = WebViewClient()

            setOnTouchListener(createTouchListener())
        }
    }

    // ==================== 加载桌宠页面 ====================

    private var petLoaded = false

    fun loadPetHtml(htmlContent: String) {
        mainHandler.post {
            overlayView?.let {
                it.loadDataWithBaseURL(
                    "file:///android_asset/",
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
                petLoaded = true
            }
        }
    }

    fun loadPetFromAsset() {
        mainHandler.post {
            overlayView?.loadUrl("file:///android_asset/pet/index.html")
            petLoaded = true
        }
    }

    fun showOverlay() {
        if (overlayView?.parent == null && windowManager != null && params != null) {
            windowManager?.addView(overlayView, params)
        }
    }

    fun hideOverlay() {
        overlayView?.let {
            if (it.parent != null) {
                windowManager?.removeView(it)
            }
        }
    }

    // ==================== 手势系统 ====================

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var lastTapWindow = 0L

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        val now = System.currentTimeMillis()
                        if (elapsed > 600) {
                            onLongPress()
                            tapCount = 0
                        } else if (now - lastTapTime < 300) {
                            tapCount++
                            if (tapCount >= 3) {
                                onMultiTap(tapCount)
                                tapCount = 0
                            } else {
                                onDoubleTap()
                            }
                        } else {
                            if (now - lastTapTime > 500) tapCount = 0
                            tapCount++
                            if (tapCount >= 3) {
                                onMultiTap(tapCount)
                                tapCount = 0
                            } else {
                                lastTapTime = now
                                onTap()
                            }
                        }
                        lastTapTime = now
                        // 上报手势
                        reportGesture(event)
                    } else {
                        // 检测快速拖拽（Fling）
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (elapsed < 500 && (Math.abs(dx) > 200 || Math.abs(dy) > 200)) {
                            onFling(dx, dy)
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun onTap() {
        callJs("window.petEngine && window.petEngine.onTap()")
    }

    private fun onDoubleTap() {
        callJs("window.petEngine && window.petEngine.onDoubleTap()")
    }

    private fun onLongPress() {
        callJs("window.petEngine && window.petEngine.onLongPress()")
    }

    private fun onMultiTap(count: Int) {
        callJs("window.petEngine && window.petEngine.onMultiTap($count)")
    }

    private fun onFling(dx: Int, dy: Int) {
        callJs("window.petEngine && window.petEngine.onFling($dx, $dy)")
    }

    private fun callJs(script: String) {
        mainHandler.post {
            overlayView?.evaluateJavascript(script, null)
        }
    }

    // ==================== 感知系统 ====================

    private var lastForegroundApp = ""
    private var appSwitchCount = 0
    private var appSwitchWindowStart = 0L

    private fun startSensors() {
        scope.launch {
            // 前台 App 轮询
            while (isActive) {
                detectForegroundApp()
                delay(3000)
            }
        }
        scope.launch {
            // Supabase 轮询（AI 推送的消息）
            while (isActive) {
                pollAiState()
                delay(5000)
            }
        }
    }

    private fun detectForegroundApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val currentTime = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            currentTime - 10000,
            currentTime
        )

        if (stats != null) {
            var foregroundApp = ""
            var lastTime = 0L
            for (usageStats in stats) {
                if (usageStats.lastTimeUsed > lastTime) {
                    lastTime = usageStats.lastTimeUsed
                    foregroundApp = usageStats.packageName
                }
            }

            if (foregroundApp != lastForegroundApp && foregroundApp.isNotEmpty()) {
                val previousApp = lastForegroundApp
                lastForegroundApp = foregroundApp

                // 快速切换检测
                val now = System.currentTimeMillis()
                if (now - appSwitchWindowStart < 60000) {
                    appSwitchCount++
                    if (appSwitchCount >= 3) {
                        callJs("window.petEngine && window.petEngine.onQuickSwitch()")
                    }
                } else {
                    appSwitchCount = 1
                    appSwitchWindowStart = now
                }

                // 通知桌宠切换了 App
                callJs("window.petEngine && window.petEngine.onAppChanged('$foregroundApp', '$previousApp')")

                // 上报给 Supabase
                reportAppUsage(foregroundApp)
            }
        }
    }

    // ==================== Supabase 通信 ====================

    private fun reportGesture(event: MotionEvent) {
        if (SUPABASE_URL.isBlank()) return
        scope.launch {
            try {
                val gestureType = when (event.action) {
                    MotionEvent.ACTION_DOWN -> "touch_down"
                    else -> "touch_up"
                }
                val json = """{"gesture_type":"$gestureType","x":${event.rawX.toInt()},"y":${event.rawY.toInt()}}"""
                supabasePost("gesture_log", json)
            } catch (_: Exception) {}
        }
    }

    private fun reportAppUsage(packageName: String) {
        if (SUPABASE_URL.isBlank()) return
        scope.launch {
            try {
                val appName = getAppName(packageName)
                val json = """{"package_name":"$packageName","app_name":"$appName"}"""
                supabasePost("app_usage", json)
            } catch (_: Exception) {}
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private suspend fun supabasePost(table: String, bodyJson: String) {
        try {
            val url = java.net.URL("$SUPABASE_URL/rest/v1/$table")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            conn.outputStream.use { it.write(bodyJson.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private suspend fun pollAiState() {
        if (SUPABASE_URL.isBlank()) return
        try {
            val url = java.net.URL("$SUPABASE_URL/rest/v1/pet_state?order=updated_at.desc&limit=1")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                // 解析并应用状态
                if (body.length > 5) {
                    mainHandler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.applyState($body)", null
                        )
                    }
                }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {}
    }

    fun pushState(stateKey: String, stateValue: String) {
        if (SUPABASE_URL.isBlank()) return
        scope.launch {
            try {
                val json = """{"state_key":"$stateKey","state_value":"$stateValue"}"""
                supabasePost("pet_state", json)
            } catch (_: Exception) {}
        }
    }

    // ==================== 通知 ====================

    private var notificationTexts = listOf(
        "🐾 在呢在呢~",
        "👀 偷偷看着你",
        "💤 好困…但不想睡",
        "😊 你认真的样子好可爱",
        "🌸 今天也一起吧",
        "🌙 还不睡呀？",
        "☕ 该喝水啦！",
        "💕 最喜欢你了",
        "🎵 一起听歌吗？",
        "📱 又在刷抖音……哼",
        "🥺 戳我戳我",
        "✨ 你终于看我了！"
    )

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐾 你的桌宠")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateNotification(text: String? = null) {
        val msg = text ?: notificationTexts.random()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(msg))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌宠",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "AI 桌宠的前台服务通知"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // ==================== 工具方法 ====================

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        scope.cancel()
        overlayView?.let {
            if (it.parent != null) {
                windowManager?.removeView(it)
            }
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
