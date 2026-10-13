package com.xambro.browser

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Mengelola "alarm peringatan" saat XamBro terdeteksi keluar dari Screen Pinning.
 *
 * Tanggung jawab:
 *  - Memutar suara alarm sistem (loop) sampai peserta mengakui peringatan.
 *  - Menggetarkan perangkat dengan pola berulang.
 *  - Mencatat jumlah pelanggaran (berapa kali peserta mencoba keluar) secara persisten
 *    selama sesi ujian berlangsung, via SharedPreferences.
 */
class ExamAlarmManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Jumlah pelanggaran (percobaan keluar) sejak sesi ujian ini dimulai. */
    fun getViolationCount(): Int = prefs.getInt(KEY_VIOLATION_COUNT, 0)

    /** Catat satu pelanggaran baru dan kembalikan jumlah total setelah pencatatan. */
    fun recordViolation(): Int {
        val count = getViolationCount() + 1
        prefs.edit().putInt(KEY_VIOLATION_COUNT, count).apply()
        return count
    }

    /** Reset penghitung pelanggaran, misalnya saat sesi ujian baru dimulai. */
    fun resetViolationCount() {
        prefs.edit().putInt(KEY_VIOLATION_COUNT, 0).apply()
    }

    /** Mulai bunyi alarm + getaran. Aman dipanggil berulang (otomatis stop yang lama dulu). */
    fun startAlarm() {
        stopAlarm()
        playAlarmSound()
        startVibration()
    }

    /** Hentikan suara alarm & getaran (dipanggil saat peserta menekan tombol "Saya Mengerti"). */
    fun stopAlarm() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: IllegalStateException) {
                // Player sudah dalam state tidak valid, aman diabaikan.
            }
            player.release()
        }
        mediaPlayer = null

        vibrator?.cancel()
    }

    private fun playAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Jika gagal memutar suara (mis. tidak ada default alarm tone), getaran tetap jalan
            // sebagai peringatan cadangan - tidak perlu menghentikan alur aplikasi.
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        val pattern = longArrayOf(0, 500, 300, 500, 300, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    companion object {
        private const val PREFS_NAME = "xambro_exam_prefs"
        private const val KEY_VIOLATION_COUNT = "pinning_violation_count"
    }
}
