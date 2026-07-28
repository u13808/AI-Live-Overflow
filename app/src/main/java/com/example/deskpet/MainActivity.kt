package com.example.deskpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.deskpet.service.OverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_USAGE_STATS = 1002
        private const val REQUEST_NOTIFICATION_PERMISSION = 1003

        // Supabase 配置 —— 启动时从这里读取
        const val SUPABASE_URL = ""
        const val SUPABASE_KEY = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnPermissionOverlay = findViewById<Button>(R.id.btnPermissionOverlay)
        val btnPermissionUsage = findViewById<Button>(R.id.btnPermissionUsage)
        val btnPermissionNotify = findViewById<Button>(R.id.btnPermissionNotify)

        // Supabase 配置注入到 Service
        OverlayService.SUPABASE_URL = SUPABASE_URL
        OverlayService.SUPABASE_KEY = SUPABASE_KEY

        // 更新权限状态
        fun updateStatus() {
            val overlayGranted = Settings.canDrawOverlays(this)
            val usageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), packageName
                )
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } else true
            val notifyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            } else true

            val status = buildString {
                appendLine("✅ 悬浮窗权限: ${if (overlayGranted) "已授权" else "未授权"}")
                appendLine("✅ 使用情况统计: ${if (usageGranted) "已授权" else "未授权"}")
                appendLine("✅ 通知权限: ${if (notifyGranted) "已授权" else "未授权"}")

                if (overlayGranted && usageGranted && notifyGranted) {
                    appendLine("\n🎉 所有权限已就绪，可以启动了！")
                } else {
                    appendLine("\n⚠️ 请先授予以上权限")
                }
            }
            statusText.text = status
        }

        btnPermissionOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } else {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            }
        }

        btnPermissionUsage.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivityForResult(intent, REQUEST_USAGE_STATS)
            }
        }

        btnPermissionNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            } else {
                Toast.makeText(this, "当前系统版本无需单独授权", Toast.LENGTH_SHORT).show()
            }
        }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("缺少悬浮窗权限")
                    .setMessage("请先授予悬浮窗权限，桌宠才能显示在屏幕上。")
                    .setPositiveButton("去授权") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }

            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "🐾 桌宠已启动！", Toast.LENGTH_SHORT).show()
            btnStart.text = "🔄 重启桌宠"
        }

        updateStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 权限页面返回后刷新状态（延迟确保系统已更新权限状态）
        findViewById<TextView>(R.id.statusText).postDelayed({
            updateStatus()
        }, 500)
    }

    private fun updateStatus() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val overlayGranted = Settings.canDrawOverlays(this)
        val usageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } else true
        val notifyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        if (overlayGranted && usageGranted && notifyGranted) {
            statusText.text = "🎉 所有权限已就绪！\n点击「启动桌宠」开始~"
        }
    }

    override fun onResume() {
        super.onResume()
        // 刷新权限状态显示
        val statusText = findViewById<TextView>(R.id.statusText)
        val overlayGranted = Settings.canDrawOverlays(this)
        val usageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } else true
        val notifyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        if (overlayGranted && usageGranted && notifyGranted) {
            statusText.text = "🎉 所有权限已就绪！\n点击「启动桌宠」开始~"
        }
    }
}
