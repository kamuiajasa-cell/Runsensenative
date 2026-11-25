# RunSense untuk Android (Kotlin)

Proyek Android Studio yang membungkus **seluruh** aplikasi RunSense (UI, mesin coach, readiness,
review lari, rencana mingguan, riwayat, progres, cuaca, sinkron Intervals.icu) sebagai aplikasi
native Kotlin. Kode aplikasi asli ada di `app/src/main/assets/www/index.html` tanpa diubah.

## Cara build
1. Buka folder ini di **Android Studio** (Koala atau lebih baru, JDK 17). Biarkan Gradle sync.
2. Jalankan ke perangkat/emulator (Android 8.0+), atau `Build > Build APK(s)`.
   Lewat terminal (setelah Android Studio membuat wrapper): `./gradlew assembleDebug`.

## Struktur
- `MainActivity.kt` : WebView, dialog alert/confirm, izin lokasi, tombol kembali.
- `WwwPathHandler.kt` : menyajikan `assets/www/*` di `https://appassets.androidplatform.net`
  dan menyisipkan `shim.js`.
- `NativeBridge.kt` : `window.RunSenseNative` untuk HTTP bebas CORS (hanya `intervals.icu` dan
  `*.open-meteo.com`), share, salin teks, dan warna status bar mengikuti tema.
- `assets/shim.js` : mengganti `fetch` ke host di atas dengan jalur Kotlin, mengisi
  `navigator.share` / `navigator.clipboard`, dan menangani tombol kembali.

## Ikon
Semua dari `icons/` di zip RunSense-mu:
- **Ikon aplikasi:** `icon-512.png` jadi lapisan depan ikon adaptif (latar navy polos).
- **Ikon bertema (Android 13+):** siluet dari `badge-96.png`.
- **Badge notifikasi:** `badge-96.png` jadi ikon kecil notifikasi (`drawable-*/ic_stat_runsense.png`),
  `icon-192.png` jadi ikon besarnya. Halaman memanggil `rsNotify(judul, isi)`; shim mengalihkannya
  ke notifikasi native. Android 13+ akan meminta izin notifikasi saat notifikasi pertama dikirim.
- Salinan web (`assets/www/icons/`) juga diganti.

## Sumber aplikasi: bundel atau URL sendiri
Default: web RunSense dibundel di APK (`assets/www/index.html`).
Untuk memuat versi web yang kamu host, isi di `gradle.properties`:
`runsenseUrl=https://domain-kamu/` lalu build ulang. Aturan:
- Harus `https`. Jembatan native (Health Connect, HTTP, share) hanya melayani halaman dari
  origin itu dan origin bundel; halaman lain dibuka di browser.
- Kalau URL tidak terjangkau, aplikasi jatuh ke versi bundel.
- **localStorage terpisah per origin.** API key dan profil di versi URL tidak terlihat di versi
  bundel (dan sebaliknya), jadi saat fallback offline kamu akan melihat layar setup kosong.

## Health Connect (hanya tulis)
Alur data: jam Huawei → Huawei Health → Intervals.icu → RunSense → Health Connect.
RunSense tidak membaca Health Connect. Di tab **Saya > Koneksi data** ada tombol
"Tulis ke Health Connect" dan sakelar "Otomatis" (menulis ±8 detik setelah data baru tersimpan).

Yang ditulis (120 hari terakhir): sesi lari/jalan beserta jarak dan elevasi, langkah harian,
detak istirahat, HRV (RMSSD), dan SpO2. Tiap record punya `clientRecordId` tetap
(`runsense-...`), jadi menulis ulang memperbarui record yang sama, bukan menggandakan.

Sengaja tidak ditulis: HR rata-rata sesi dan tidur (data Intervals tidak punya sampel HR
maupun jam tidur/bangun). Pembacaan harian (detak istirahat, HRV, SpO2) diberi cap waktu
06:00 lokal karena tidak punya jam asli.

Build: `connect-client` 1.1.0 butuh compileSdk 36, karena itu AGP 8.9.1 dan Gradle 8.11.1.

## Catatan
- Data (API key, profil, cache) tersimpan di localStorage WebView, privat untuk aplikasi.
  `allowBackup` dimatikan agar API key tidak ikut backup.
- Fitur koneksi jam Huawei langsung (BLE, `RunNowBridge` di komentar kode) tidak ikut: itu butuh
  modul native terpisah yang tidak ada di file HTML. Tombolnya tersembunyi, dan jalur resmi
  lewat Intervals.icu tetap jalan.
- Font Inter dimuat dari Google Fonts; tanpa internet jatuh ke font sistem.
