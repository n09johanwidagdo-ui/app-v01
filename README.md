# Xambro

Aplikasi browser Android berbasis WebView dengan mode kiosk:
- Mencegah screenshot & screen recording (`FLAG_SECURE`)
- Mencegah mode split-screen (`resizeableActivity="false"`)
- Mengunci layar ke aplikasi ini (screen pinning / Lock Task)
- Tombol Back & volume diblokir agar tidak mudah keluar

## 1. Cara build APK secara online (GitHub Actions)

1. Buat repository baru di GitHub (publik atau privat).
2. Push seluruh isi folder project ini ke repo tersebut:
   ```bash
   git init
   git add .
   git commit -m "Initial commit - Xambro kiosk browser"
   git branch -M main
   git remote add origin https://github.com/USERNAME/NAMA_REPO.git
   git push -u origin main
   ```
3. Buka tab **Actions** di repo GitHub Anda. Workflow "Build Xambro APK" akan berjalan otomatis.
4. Setelah selesai (biasanya 3-6 menit), buka hasil run tersebut dan unduh artifact
   **xambro-debug-apk** — isinya file `app-debug.apk` yang siap diinstal di Android.

Build ini menghasilkan **debug APK** (tidak perlu keystore). Untuk rilis produksi/Play Store,
tambahkan signing config dengan keystore sendiri (lihat bagian 4).

## 2. Ganti URL yang dimuat

Edit `app/src/main/res/values/strings.xml`:
```xml
<string name="start_url">https://situs-anda.com</string>
```

## 3. Mode Kiosk

Ada dua level penguncian:

### a. Screen Pinning standar (aktif otomatis, tanpa setup tambahan)
`MainActivity` otomatis memanggil `startLockTask()` saat dibuka. Ini mengunci layar ke
aplikasi, menyembunyikan tombol Recent, dan memblokir Back. **Catatan:** pada screen pinning
standar (tanpa Device Owner), Android tetap mengizinkan user melepas pin dengan cara
menahan tombol Back + Recent secara bersamaan selama beberapa detik — ini perilaku sistem
Android yang tidak bisa dihilangkan tanpa Device Owner.

### b. Mode Kiosk Penuh (Device Owner) — tidak bisa keluar sama sekali
Untuk kunci penuh (dipakai di tablet kasir/sekolah), app harus didaftarkan sebagai
**Device Owner**. Syaratnya device harus dalam kondisi belum ada akun Google
(baru di-factory reset), karena Device Owner tidak bisa di-set di device yang sudah
punya akun aktif.

Langkah provisioning via ADB (device sudah di-reset, belum setup akun apapun):
```bash
adb install app-debug.apk
adb shell dpm set-device-owner com.xambro.browser/.KioskDeviceAdminReceiver
```

Setelah menjadi Device Owner, `startLockTask()` di kode akan otomatis mengunci penuh
(karena `setLockTaskPackages()` sudah dipanggil di `enterKioskModeIfPossible()`), dan
user tidak bisa keluar tanpa aksi dari dalam aplikasi itu sendiri (misalnya tombol
"Keluar" tersembunyi dengan PIN admin yang bisa Anda tambahkan sendiri di kode).

Alternatif provisioning tanpa ADB: generate QR code enrollment (lewat Android Management
API / EMM) agar device otomatis jadi Device Owner saat setup pabrik — dipakai untuk
deploy massal ke banyak device.

## 4. Fitur Alarm Peringatan Keluar dari Sematan Layar

Cocok untuk skenario ujian online: jika peserta mencoba keluar dari Screen Pinning
(tekan Home, buka Recent Apps, atau berhasil melepas pin), XamBro akan:

1. **Mendeteksi** — `MainActivity` memantau `onUserLeaveHint()` (sinyal paling awal saat
   user menekan Home/Recents) dan membandingkan status `lockTaskModeState` di
   `onPause()`/`onStop()`/`onResume()` untuk tahu apakah sematan baru saja lepas.
2. **Alarm otomatis** — `ExamAlarmManager` membunyikan nada alarm sistem (loop) + getaran
   berpola, lalu mencatat jumlah pelanggaran secara persisten (`SharedPreferences`).
3. **Dialog peringatan** — begitu aplikasi kembali ke layar depan, muncul dialog modal
   (tidak bisa ditutup dengan Back/tap luar) bertuliskan **"PERINGATAN! Anda mencoba
   keluar dari aplikasi ujian."** beserta nomor pelanggaran ke berapa. Alarm baru berhenti
   setelah peserta menekan **"Saya Mengerti"**, dan aplikasi otomatis dikunci ulang ke mode
   kiosk (`startLockTask()`).

File terkait:
- `app/src/main/java/com/xambro/browser/ExamAlarmManager.kt` — logika suara alarm, getaran,
  dan penghitung pelanggaran.
- `app/src/main/java/com/xambro/browser/MainActivity.kt` — deteksi siklus hidup & pemicu alarm.
- `app/src/main/res/values/strings.xml` — teks peringatan (`pinning_violation_*`), bisa diubah
  sesuai kebutuhan institusi/sekolah.

**Mengatur ulang/menonaktifkan:**
- Untuk menonaktifkan fitur ini sepenuhnya, ubah `private val examModeEnabled = true` menjadi
  `false` di `MainActivity.kt`.
- Untuk mereset penghitung pelanggaran di awal sesi ujian baru (misalnya dipanggil dari halaman
  login web lewat JavaScript bridge), gunakan `examAlarmManager.resetViolationCount()`.
- Jumlah pelanggaran saat ini bisa dibaca kapan saja lewat `examAlarmManager.getViolationCount()`
  — berguna jika ingin dikirim ke server pengawas ujian.

## 5. Menandatangani APK untuk rilis (opsional)

Jika ingin APK release yang ditandatangani (bukan debug):

1. Buat keystore:
   ```bash
   keytool -genkey -v -keystore xambro.keystore -alias xambro -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Simpan sebagai GitHub Secret (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
3. Tambahkan langkah signing di workflow dan `signingConfig` di `app/build.gradle`.

(Beri tahu saya jika Anda ingin saya buatkan langsung konfigurasi signing + workflow release-nya.)

## 6. Struktur Project

```
xambro/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/xambro/browser/
│       │   ├── MainActivity.kt
│       │   ├── ExamAlarmManager.kt
│       │   └── KioskDeviceAdminReceiver.kt
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/ (strings, colors, themes)
│           ├── xml/device_admin_policies.xml
│           └── mipmap-*/ic_launcher.png
├── .github/workflows/build.yml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Catatan Legal/Etika

Fitur anti-screenshot dan kiosk lock cocok untuk kebutuhan sah seperti aplikasi ujian
online, kios informasi publik, display digital, atau perangkat terkelola perusahaan/sekolah.
Jika aplikasi ini akan dipasang di perangkat milik orang lain, pastikan pengguna perangkat
mengetahui dan menyetujui pemasangannya — khususnya untuk fitur Device Owner yang memberi
kontrol penuh atas perangkat tersebut.
