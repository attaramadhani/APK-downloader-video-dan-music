# AnyDrop — Video & Music Downloader untuk Android

AnyDrop adalah aplikasi Android untuk mengunduh video dan musik dari media sosial dan situs web
publik, lalu menyimpannya ke galeri / pemutar musik HP agar bisa ditonton atau didengar **offline**.

- Mendukung YouTube, TikTok, Instagram, Facebook, Twitter/X, Reddit, SoundCloud, Telegram, dan
  1000+ situs lain lewat mesin **yt-dlp** yang diperbarui otomatis.
- Pilih kualitas video (360p – 4K bila tersedia) atau **audio saja** untuk musik.
- Unduhan berjalan di latar belakang, bisa **dijeda dan dilanjutkan**.
- Dibuat dengan Kotlin + Jetpack Compose.

> ⚠️ Gunakan hanya untuk konten milik Anda sendiri, konten berlisensi bebas, atau konten yang
> diizinkan pemiliknya untuk diunduh. Hormati hak cipta dan ketentuan layanan tiap platform.
> Aplikasi ini **tidak** membobol DRM (mis. Netflix, Spotify terenkripsi, video berbayar).

---

## 📲 Cara Memasang APK

1. Buka tab **[Actions](../../actions)** di repo ini, pilih build terbaru yang bertanda ✅ hijau.
2. Gulir ke bawah ke bagian **Artifacts**, klik **apk** untuk mengunduh file zip
   (harus login GitHub).
3. Ekstrak zip → pindahkan `release/app-release.apk` ke HP.
4. Buka file APK di HP. Bila muncul peringatan, izinkan **"Instal dari sumber tidak dikenal"**
   untuk aplikasi File Manager / browser Anda.
5. **Jika sudah ada versi lama AnyDrop di HP dan pemasangan gagal**, hapus (uninstall) dulu
   versi lama, lalu pasang lagi. (Ini terjadi bila APK lama dan baru ditandatangani kunci berbeda.)

**Kebutuhan:** Android 7.0 (API 24) ke atas, HP berprosesor ARM (hampir semua HP Android).

---

## 🚀 Cara Memakai

### 1. Masukkan link
Ada dua cara:
- **Bagikan langsung:** di YouTube/TikTok/Instagram/dll. tekan **Bagikan / Share → AnyDrop**.
  Link otomatis masuk dan langsung dianalisis.
- **Tempel manual:** salin link video, buka AnyDrop, tekan **Tempel**, lalu tekan
  **Ambil Info Media**.

> Pertama kali aplikasi dibuka, mesin yt-dlp butuh beberapa detik untuk menyiapkan diri.
> Bila analisis pertama terasa lambat, itu normal.

### 2. Pilih format
Setelah analisis selesai, muncul judul, thumbnail, dan daftar format:
- Tab **Video MP4** → pilih kualitas (mis. *Full HD 1080p*, *HD 720p*).
- Tab **Audio Musik** → untuk menyimpan suaranya saja (MP3 / M4A), cocok untuk lagu.

Ukuran file perkiraan ditampilkan di samping tiap format bila server memberikannya.

### 3. Unduh
Tekan **Download Sekarang**. Progres, kecepatan, dan sisa waktu tampil di menu
**Pengunduh Video** dan di notifikasi.
- Tekan **⏸** untuk menjeda, **▶** untuk melanjutkan dari titik terakhir.
- Saat unduhan gagal, aplikasi otomatis mencoba sekali lagi dengan mesin cadangan (yt-dlp).
  Bila tetap gagal, buka **Riwayat Unduhan** dan tekan ikon **🔄** pada item tersebut untuk mencoba ulang.

### 4. Temukan file Anda
- Video tersimpan di **Galeri → folder `Movies/AnyDrop`**
- Audio tersimpan di **Musik → folder `Music/AnyDrop`**
- Semua file juga tercatat di menu **Riwayat Unduhan** (bisa diputar ulang atau dihapus dari daftar).

### Fitur tambahan (menu samping)
- **Login Telegram (VIP):** masuk ke akun Telegram Anda agar bisa mengunduh media dari channel
  privat yang **Anda sendiri sudah menjadi anggotanya**.
- **Alat Gabung Media:** gabungkan file video tanpa suara + file audio dari HP menjadi satu MP4.

---

## 🛠️ Mengatasi Masalah

| Masalah | Solusi |
|---|---|
| "Video ini privat atau butuh login" | Video hanya untuk pengikut/teman. Hanya video publik yang bisa diunduh. |
| "Dibatasi wilayah (geo-block)" | Video tidak tersedia di negara Anda. |
| "Dilindungi DRM" | Konten berbayar/terenkripsi memang tidak bisa diunduh. |
| "Server menolak akses (HTTP 403)" / "link kedaluwarsa" | Analisis ulang link-nya (tekan **Ambil Info Media** lagi), lalu unduh ulang. |
| "Situs ini belum didukung" | Pastikan link mengarah ke **halaman video** (bukan beranda/profil). |
| Unduhan berhenti di tengah | Periksa koneksi, lalu tekan ▶ — unduhan dilanjutkan, tidak mulai dari 0. |
| Audio tidak bisa diputar di pemutar musik | Pastikan memilih format di tab **Audio Musik**, bukan video. |
| Analisis lambat setelah lama tidak dipakai | yt-dlp sedang memperbarui diri (otomatis tiap 3 hari). Tunggu sebentar. |

---

## 👩‍💻 Untuk Pengembang

### Build di komputer
1. Buka folder proyek di **Android Studio** (Ladybug / 2024.2 atau lebih baru), JDK 17.
2. Tunggu Gradle sync selesai.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

### Build otomatis (GitHub Actions)
Setiap `push` ke branch `main` otomatis mem-build APK debug & release
(`.github/workflows/build-apk.yml`). Hasilnya ada di tab **Actions → Artifacts**.
Bila build gagal, ringkasan error tampil sebagai anotasi pada halaman run.

### Struktur kode

```
app/src/main/java/com/downloader/allinone/
├── engine/        # Ekstraktor link → daftar format
│   ├── MediaExtractorManager.kt   # Urutan: ekstraktor khusus → yt-dlp → sniffer universal
│   ├── YtDlpEngine.kt             # yt-dlp (1000+ situs, auto-update)
│   ├── YouTube/TikTok/Twitter/SoundCloud/Telegram/SpotifyExtractor.kt
│   ├── UniversalExtractor.kt      # Reddit, Facebook, Instagram, OpenGraph, Cobalt
│   └── DeepWebVideoSniffer.kt     # HTML, iframe, WebView headless untuk situs umum
├── downloader/
│   ├── FileDownloader.kt          # Unduh langsung (resume), DASH, fallback yt-dlp
│   └── HlsDownloader.kt           # Streaming .m3u8 (AES-128, audio terpisah, resume)
├── service/DownloadService.kt     # Foreground service + notifikasi
├── util/                          # MediaMuxer, MediaStore, riwayat, notifikasi
├── model/                         # MediaInfo, MediaFormat, DownloadTask
└── ui/                            # Jetpack Compose (MainScreen, modal Telegram & Merger)
```

### Library utama
- [youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp + ffmpeg)
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- OkHttp, Cronet, Jsoup, Rhino, Coil, Jetpack Compose Material 3

Catatan riwayat perbaikan lengkap ada di [`PERUBAHAN.md`](PERUBAHAN.md).
