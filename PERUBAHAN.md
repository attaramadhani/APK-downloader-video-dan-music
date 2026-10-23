# Catatan Perbaikan & Peningkatan

## Bug utama yang menyebabkan banyak video gagal diunduh

1. **Header unduhan hilang (penyebab HTTP 403 paling sering).**
   `DownloadService` membangun ulang `MediaFormat` dari Intent tanpa `customHeaders`
   (Referer, Cookie, User-Agent) dan tanpa info lain. Server CDN (Twitter/X, TikTok,
   situs dengan player embed) menolak permintaan tanpa header itu. Sekarang format dikirim
   utuh sebagai JSON (`MediaFormat.toJson()/fromJson()`), termasuk saat dilanjutkan dari riwayat.
2. **Sniffer WebView mati di build release.** R8 mengganti nama method `@JavascriptInterface`,
   sehingga `window.AnyDropSniffer.onVideoDetected` tidak pernah terpanggil di APK release.
   Ditambahkan aturan ProGuard yang menjaga method tersebut.
3. **HLS (.m3u8) memilih varian terakhir, bukan kualitas terbaik**, mengabaikan trek audio terpisah
   (video jadi bisu), dan menghitung IV AES-128 dari 0 alih-alih `#EXT-X-MEDIA-SEQUENCE`
   (hasil video rusak/hijau).
4. **Opsi "Audio MP3" menyimpan file video dengan ekstensi .mp3.** Sekarang trek audio diambil
   ke `.m4a` (AAC tanpa re-encode) atau dikonversi ke MP3 lewat yt-dlp + ffmpeg.

## Fitur baru

- **Mesin yt-dlp** (`engine/YtDlpEngine.kt`, library `youtubedl-android` 0.18.1 + ffmpeg):
  mendukung 1000+ situs, menangani DASH/HLS, cookie, header, dan merge video+audio.
  yt-dlp diperbarui otomatis tiap 3 hari, jadi saat situs berubah cukup update tanpa rilis APK baru.
- **Urutan ekstraksi baru** (`MediaExtractorManager`): ekstraktor khusus → yt-dlp → sniffer universal.
  Pesan error sekarang menyebutkan penyebab sebenarnya (privat, geo-block, DRM, timeout, dll.).
- **Cadangan otomatis saat unduh**: bila pengunduh bawaan gagal (403, link kedaluwarsa), unduhan
  diulang otomatis memakai yt-dlp dari URL halaman aslinya.
- **Pengunduh HLS baru** (`downloader/HlsDownloader.kt`): varian kualitas tertinggi, audio terpisah,
  `EXT-X-BYTERANGE`, init segment fMP4, 4 segmen paralel, retry bertahap, **bisa dijeda & dilanjutkan**,
  lalu di-remux ke MP4 asli agar tampil di galeri.
- **Sniffer WebView lebih pintar**: hook XHR/fetch dipasang sebelum script halaman berjalan
  (`androidx.webkit` document-start script), header asli tiap request media (Referer dari iframe)
  ikut disimpan, cookie pihak ketiga diizinkan, menunggu master playlist sebelum selesai, timeout 17 dtk.
- **Spotify**: metadata dari Spotify, audio dicari di YouTube lewat yt-dlp sebagai cadangan.
  (Tidak membobol enkripsi/DRM Spotify.)
- Pesan error HTTP yang jelas (403/404/429/5xx), deteksi halaman HTML yang menyamar sebagai video,
  retry dengan jeda bertahap, cache DNS ber-TTL 10 menit, timeout pendek untuk instance Cobalt.

## Yang perlu diperhatikan saat build

- Ukuran APK bertambah karena yt-dlp membawa Python + ffmpeg. Untuk menekan ukuran, ABI dibatasi ke
  `arm64-v8a` dan `armeabi-v7a` (tambahkan `x86_64` di `build.gradle.kts` bila menguji di emulator).
- Pertama kali aplikasi dibuka, yt-dlp perlu beberapa detik untuk menyiapkan diri di background.
- `packaging.jniLibs.useLegacyPackaging = true` wajib untuk yt-dlp (jangan dihapus).

## File yang diubah / ditambah

| File | Perubahan |
|---|---|
| `engine/YtDlpEngine.kt` | **Baru** — ekstraksi & unduh via yt-dlp |
| `downloader/HlsDownloader.kt` | **Baru** — pengunduh HLS |
| `downloader/FileDownloader.kt` | Alur yt-dlp, fallback otomatis, HLS baru, audio-only, pesan error |
| `engine/MediaExtractorManager.kt` | Urutan ekstraksi baru + pesan penyebab gagal |
| `engine/DeepWebVideoSniffer.kt` | Header per-request, hook awal, timeout & penilaian lebih baik |
| `service/DownloadService.kt` | Format dikirim utuh (header tidak hilang) |
| `util/DownloadHistoryManager.kt` | Riwayat menyimpan format lengkap |
| `util/MediaMuxerHelper.kt` | `prepareAudioOnly()` untuk audio asli |
| `model/MediaFormat.kt` | Field `sourcePageUrl`, `ytdlpFormatId`, serialisasi JSON |
| `engine/SpotifyExtractor.kt` | Cadangan pencarian audio via yt-dlp |
| `engine/UniversalExtractor.kt` | Timeout pendek Cobalt |
| `engine/EncryptedDnsResolver.kt` | Cache DNS dengan TTL |
| `AllInOneApp.kt` | Inisialisasi & update yt-dlp di background |
| `app/build.gradle.kts` | Dependensi yt-dlp, ffmpeg, androidx.webkit; ABI filter; legacy packaging |
| `app/proguard-rules.pro` | Keep `@JavascriptInterface`, Cronet, yt-dlp, jsoup |

Salinan kode sebelum perbaikan ada di folder `_backup_sebelum_perbaikan/`.
