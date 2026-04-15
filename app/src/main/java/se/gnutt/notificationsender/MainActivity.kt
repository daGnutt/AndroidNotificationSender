package se.gnutt.notificationsender

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var etEndpoint: TextInputEditText
    private lateinit var etUserId: TextInputEditText
    private lateinit var btnScanQr: Button
    private lateinit var btnSave: Button
    private lateinit var btnGrantPermission: Button
    private lateinit var btnRefresh: Button
    private lateinit var tvSaveStatus: TextView
    private lateinit var tvListenerStatus: TextView

    private lateinit var tvFcmStatus: TextView

    private lateinit var settings: SettingsManager
    private val apiClient = ApiClient()
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val serverUrl = result.data?.getStringExtra(QrScanActivity.RESULT_SERVER_URL) ?: return@registerForActivityResult
            val userId = result.data?.getStringExtra(QrScanActivity.RESULT_USER_ID) ?: return@registerForActivityResult
            etEndpoint.setText(serverUrl)
            etUserId.setText(userId)
            setStatus("QR scanned — tap Save & Verify to confirm.", isError = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etEndpoint = findViewById(R.id.etEndpoint)
        etUserId = findViewById(R.id.etUserId)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnSave = findViewById(R.id.btnSave)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvSaveStatus = findViewById(R.id.tvSaveStatus)
        tvListenerStatus = findViewById(R.id.tvListenerStatus)
        tvFcmStatus = findViewById(R.id.tvFcmStatus)

        settings = SettingsManager(this)

        etEndpoint.setText(settings.endpoint)
        etUserId.setText(settings.userId)

        btnScanQr.setOnClickListener {
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
        btnSave.setOnClickListener { saveAndVerify() }

        btnRefresh.setOnClickListener {
            sendBroadcast(Intent(NotificationSyncService.ACTION_REFRESH).setPackage(packageName))
            btnRefresh.isEnabled = false
            btnRefresh.text = "Refreshing…"
            uiScope.launch {
                kotlinx.coroutines.delay(3000)
                btnRefresh.isEnabled = true
                btnRefresh.text = getString(R.string.btn_refresh)
            }
        }

        btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun saveAndVerify() {
        val endpoint = etEndpoint.text.toString().trimEnd('/')
        val userId = etUserId.text.toString().trim()

        if (endpoint.isBlank() || userId.isBlank()) {
            setStatus("Please fill in all fields.", isError = true)
            return
        }

        settings.endpoint = endpoint
        settings.userId = userId

        setStatus("Verifying connection…", isError = false)
        btnSave.isEnabled = false

        uiScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    apiClient.validateUser(endpoint, userId)
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            btnSave.isEnabled = true
            if (error == null) {
                setStatus("✓ Settings saved. User verified.", isError = false)
            } else {
                setStatus("⚠ Could not verify user: $error", isError = true)
            }
            updateStatus()
        }
    }

    private fun updateStatus() {
        if (isNotificationListenerEnabled()) {
            tvListenerStatus.text = "✓ Notification listener is active"
            tvListenerStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            btnGrantPermission.visibility = View.GONE
        } else {
            tvListenerStatus.text = "⚠ Notification access not granted — tap the button below"
            tvListenerStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            btnGrantPermission.visibility = View.VISIBLE
        }

        if (settings.fcmToken != null) {
            tvFcmStatus.text = getString(R.string.fcm_status_active)
            tvFcmStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvFcmStatus.text = getString(R.string.fcm_status_polling)
            tvFcmStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        }
    }

    private fun setStatus(message: String, isError: Boolean) {
        tvSaveStatus.text = message
        tvSaveStatus.setTextColor(
            if (isError) getColor(android.R.color.holo_red_dark)
            else getColor(android.R.color.holo_green_dark)
        )
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            flat.split(":").forEach { name ->
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == packageName) return true
            }
        }
        return false
    }
}

