package com.example.backgroundstream

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.backgroundstream.databinding.ActivityMainBinding
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
            continueGrantAllIfNeeded()
        }

    private var grantAllInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnGrantAll.setOnClickListener {
            grantAllInProgress = true
            continueGrantAllIfNeeded()
        }
        binding.btnGrantPermission.setOnClickListener { openNotificationListenerSettings() }
        binding.btnPostNotifications.setOnClickListener { requestPostNotifications() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnAddTile.setOnClickListener { requestAddQuickSettingsTile() }

        playEnterAnimation()
    }

    override fun onResume() {
        super.onResume()
        if (isNotificationListenerEnabled()) {
            YouTubeNotificationListener.requestRebindIfPossible(this)
        }
        refreshStatus()
        if (grantAllInProgress) {
            continueGrantAllIfNeeded()
        }
    }

    private fun playEnterAnimation() {
        val targets = listOf(
            binding.statusCard,
            binding.btnGrantAll,
            binding.btnGrantPermission,
            binding.btnPostNotifications,
            binding.btnBattery,
            binding.btnAddTile
        )
        targets.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 28f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80L * index)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun continueGrantAllIfNeeded() {
        if (!grantAllInProgress) return

        when {
            !isNotificationListenerEnabled() -> openNotificationListenerSettings()
            !isPostNotificationsGranted() -> requestPostNotifications()
            !isBatteryOptimizationIgnored() -> requestIgnoreBatteryOptimizations()
            else -> {
                grantAllInProgress = false
                requestAddQuickSettingsTile()
                refreshStatus()
            }
        }
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refreshStatus()
            continueGrantAllIfNeeded()
            return
        }
        if (isPostNotificationsGranted()) {
            continueGrantAllIfNeeded()
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isBatteryOptimizationIgnored()) {
            continueGrantAllIfNeeded()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
    }

    private fun requestAddQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.tile_add_unsupported, Toast.LENGTH_LONG).show()
            return
        }

        val statusBarManager = getSystemService(StatusBarManager::class.java) ?: return
        val tileComponent = ComponentName(this, BackgroundStreamTileService::class.java)
        val executor = Executor { command -> command.run() }

        statusBarManager.requestAddTileService(
            tileComponent,
            getString(R.string.tile_label),
            Icon.createWithResource(this, android.R.drawable.ic_media_play),
            executor
        ) {
            runOnUiThread {
                Toast.makeText(this, R.string.tile_add_requested, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        bindBadge(binding.badgeListener, isNotificationListenerEnabled())
        bindBadge(binding.badgePost, isPostNotificationsGranted())
        bindBadge(binding.badgeBattery, isBatteryOptimizationIgnored())
        // Tile can't be detected reliably; show as needed until user adds it.
        bindBadge(binding.badgeTile, false, neededLabel = getString(R.string.status_missing))
    }

    private fun bindBadge(
        badge: TextView,
        ready: Boolean,
        neededLabel: String = getString(R.string.status_missing)
    ) {
        if (ready) {
            badge.text = getString(R.string.status_ok)
            badge.setBackgroundResource(R.drawable.bg_badge_ok)
            badge.setTextColor(ContextCompat.getColor(this, R.color.ok_text))
        } else {
            badge.text = neededLabel
            badge.setBackgroundResource(R.drawable.bg_badge_missing)
            badge.setTextColor(ContextCompat.getColor(this, R.color.missing_text))
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(this, YouTubeNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun isPostNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
