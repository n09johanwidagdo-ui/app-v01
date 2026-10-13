package com.xambro.browser

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Diperlukan agar aplikasi bisa didaftarkan sebagai Device Admin / Device Owner.
 * Device Owner adalah syarat untuk mode kiosk PENUH (lock task tanpa bisa keluar sama sekali).
 * Lihat README.md bagian "Mode Kiosk Penuh (Device Owner)" untuk cara provisioning-nya.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Xambro device admin diaktifkan", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "Xambro device admin dinonaktifkan", Toast.LENGTH_SHORT).show()
    }
}
