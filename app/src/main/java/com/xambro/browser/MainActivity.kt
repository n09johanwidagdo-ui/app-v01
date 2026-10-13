package com.xambro.browser

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity - WebView kiosk shell untuk ujian online.
 *
 * Fitur yang diaktifkan di sini:
 *  1. FLAG_SECURE -> mencegah screenshot & screen recording.
 *  2. resizeableActivity="false" di Manifest -> mencegah aplikasi masuk mode split-screen.
 *  3. startLockTask() -> screen pinning, mengunci user di dalam app ini.
 *  4. Override tombol Back & volume agar user tidak mudah keluar.
 *  5. Alarm peringatan XamBro -> mendeteksi saat aplikasi lepas dari sematan layar
 *     (atau peserta mencoba keluar), lalu membunyikan alarm + menampilkan dialog
 *     peringatan begitu aplikasi kembali ke layar depan. Lihat ExamAlarmManager.kt.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var examAlarmManager: ExamAlarmManager

    /** Set true untuk mengaktifkan fitur alarm pelanggaran sematan layar (mode ujian). */
    private val examModeEnabled = true

    /** Menandai ada pelanggaran yang alarm-nya sedang berbunyi & dialognya belum ditampilkan. */
    private var pinningViolationPending = false

    /** Jumlah pelanggaran yang akan ditunjukkan di dialog peringatan berikutnya. */
    private var lastViolationCount = 0

    /** Debounce supaya satu insiden keluar tidak tercatat dua kali (onUserLeaveHint + onPause/onStop). */
    private var lastViolationTimestamp = 0L
    private val violationDebounceMs = 2000L

    /** Melacak apakah aplikasi memang sedang dalam status terkunci (lock task) sebelumnya. */
    private var wasLockTaskActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- 1) Cegah screenshot & screen recording ---
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        examAlarmManager = ExamAlarmManager(applicationContext)

        setupWebView()
        webView.loadUrl(getString(R.string.start_url))

        enterKioskModeIfPossible()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.visibility = if (newProgress < 100) android.view.View.VISIBLE else android.view.View.GONE
                progressBar.progress = newProgress
            }
        }
    }

    /**
     * Mengaktifkan screen pinning (Lock Task Mode).
     *
     * - Jika app ini sudah didaftarkan sebagai Device Owner, startLockTask() mengunci penuh.
     * - Jika bukan Device Owner, startLockTask() tetap jalan sebagai screen pinning standar
     *   Android (user masih bisa keluar lewat gesture tahan Back + Recent).
     */
    private fun enterKioskModeIfPossible() {
        try {
            val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
            if (isDeviceOwner) {
                devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))
            }
            startLockTask()
        } catch (e: SecurityException) {
            // Device belum di-provision sebagai Device Owner / lock task tidak diizinkan.
        }
    }

    /** Blokir tombol Back agar tidak keluar dari aplikasi. */
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
        // Sengaja tidak memanggil super.onBackPressed() supaya app tidak tertutup.
    }

    /** Blokir tombol volume (opsional, bisa dihapus jika tidak diperlukan). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Dipanggil sistem SEBELUM activity kehilangan foreground karena aksi user yang disengaja
     * (tombol Home, membuka Recent Apps, dsb). Ini sinyal paling awal & paling andal bahwa
     * peserta sedang mencoba keluar dari aplikasi ujian -> langsung picu alarm di sini.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (examModeEnabled) {
            handlePossiblePinningExit()
        }
    }

    override fun onPause() {
        super.onPause()
        checkLockTaskExitAndAlarm()
    }

    override fun onStop() {
        super.onStop()
        checkLockTaskExitAndAlarm()
    }

    /**
     * Langkah 1 (Deteksi): periksa apakah sesi sematan layar sudah tidak aktif padahal
     * sebelumnya aktif -> berarti aplikasi baru saja dilepas dari Screen Pinning.
     */
    private fun checkLockTaskExitAndAlarm() {
        if (!examModeEnabled) return

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLockTaskActiveNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }

        if (wasLockTaskActive && !isLockTaskActiveNow) {
            // Sematan baru saja lepas -> ini pelanggaran yang pasti, picu alarm.
            handlePossiblePinningExit()
        }
        wasLockTaskActive = isLockTaskActiveNow
    }

    /**
     * Langkah 2 (Alarm Peringatan Otomatis): catat pelanggaran, bunyikan alarm + getaran.
     * Dialog visual baru ditampilkan saat activity kembali ke foreground (lihat onResume),
     * karena saat metode ini dipanggil activity mungkin sedang dalam proses berpindah ke
     * latar belakang sehingga dialog belum tentu bisa langsung muncul.
     */
    private fun handlePossiblePinningExit() {
        val now = System.currentTimeMillis()
        if (now - lastViolationTimestamp < violationDebounceMs) {
            // Insiden yang sama baru saja tercatat (mis. onUserLeaveHint lalu onPause) -> jangan dobel.
            return
        }
        lastViolationTimestamp = now

        lastViolationCount = examAlarmManager.recordViolation()
        examAlarmManager.startAlarm()
        pinningViolationPending = true
    }

    override fun onResume() {
        super.onResume()
        // Jaga-jaga: jika lock task sempat lepas, kunci lagi.
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                enterKioskModeIfPossible()
            }
            wasLockTaskActive = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            wasLockTaskActive = am.isInLockTaskMode
        }

        if (pinningViolationPending) {
            showViolationWarningDialog()
        }
    }

    /**
     * Langkah 2 lanjutan: tampilkan pesan peringatan ke peserta. Dialog ini tidak bisa
     * ditutup dengan tombol Back atau tap di luar area (setCancelable(false)) -- peserta
     * WAJIB menekan tombol konfirmasi agar alarm berhenti dan sesi ujian dilanjutkan.
     */
    private fun showViolationWarningDialog() {
        pinningViolationPending = false

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pinning_violation_title))
            .setMessage(getString(R.string.pinning_violation_message, lastViolationCount))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.pinning_violation_confirm)) { dialog, _ ->
                examAlarmManager.stopAlarm()
                dialog.dismiss()
                enterKioskModeIfPossible()
            }
            .show()
    }

    override fun onDestroy() {
        examAlarmManager.stopAlarm()
        try {
            stopLockTask()
        } catch (e: IllegalStateException) {
            // Lock task memang belum/tidak sedang aktif, aman diabaikan.
        }
        super.onDestroy()
    }
}
