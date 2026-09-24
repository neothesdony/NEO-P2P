# Manual Pengguna NEO-P2P

**Versi:** v0.1.2 (transport RNS/LXMF)
**Platform:** Android (min SDK 26, target SDK 36)
**Jaringan:** Bitcoin **mainnet** — uang sungguhan. Periksa setiap alamat sebelum mengirim.

---

## 1. Apa itu NEO-P2P?

NEO-P2P adalah aplikasi jual-beli Bitcoin peer-to-peer untuk Indonesia. Tanpa server, tanpa akun, tanpa KYC. Penjual dan pembeli bertemu langsung di aplikasi — tidak perlu nomor telepon, tidak perlu email.

- **Identitas** = sepasang kunci kriptografi dari frasa seed 12 kata. Itu saja.
- **Penemuan & pesan** = Reticulum Network Stack (RNS) + LXMF. Ponsel Anda terhubung ke node transport komunitas — anggap saja feri paket. Node tidak bisa membaca pesan Anda, apalagi menyentuh dana Anda.
- **Escrow** = multisig 2-of-3 on-chain yang nyata. Penjual menyetor BTC ke alamat yang butuh **2 dari 3 tanda tangan** (penjual, pembeli, arbiter) untuk dibelanjakan. Tidak ada yang bisa kabur bawa uang.
- **Chat** = terenkripsi end-to-end (E2EE v2 double ratchet: forward secrecy + post-compromise security). Hanya Anda dan rekan transaksi yang bisa membacanya.
- **Biaya** = **0,5%, dibayar penjual saja**. Pembeli tidak bayar apa pun dan menerima BTC penuh.

> ⚠️ **Peringatan mainnet:** aplikasi ini berjalan di Bitcoin **mainnet**. BTC yang tampil di layar bernilai uang sungguhan. Perlakukan setiap transaksi sebagai transaksi nyata.

---

## 2. Pemasangan

1. Pasang **APK rilis bertanda tangan** yang disediakan pengembang (`app-release.apk`) untuk uang sungguhan. APK debug hanya untuk pengujian pengembang — build debug menolak berjalan di mainnet.
2. `adb install app-release.apk` — atau salin APK ke ponsel lalu ketuk.
3. Android mungkin memperingatkan soal sumber tidak dikenal. Izinkan saja.
4. Buka **NEO-P2P**.

**Syarat peluncuran pertama:**
- Koneksi internet (untuk menjangkau node transport RNS).
- Kunci layar perangkat (PIN/pola/sidik jari) — dipakai aplikasi untuk melindungi kunci Anda. Tanpa kunci layar, identitas tetap terkunci dan P2P tidak berjalan.
- Izin notifikasi — supaya Anda tahu saat penawaran cocok, escrow didanai, atau pembayaran dikonfirmasi.

---

## 3. Pertama Kali: Onboarding

### 3.1 Pernyataan risiko
Baca peringatan dengan saksama. Ini bukan formalitas: trading P2P punya risiko nyata (penipuan, bukti transfer palsu, chargeback, rekening dibekukan). Ketuk **I Understand and Accept** (Saya Mengerti dan Menerima) untuk lanjut.

### 3.2 Buat identitas Anda
- Ketuk **Generate Identity** (Buat Identitas). Frasa seed 12 kata dibuat **hanya di perangkat Anda**.
- Nama panggilan opsional (mis. `trader_42`) — tampil ke rekan transaksi. Bisa diubah nanti di Profil.

### 3.3 Cadangkan frasa seed Anda — LANGKAH PALING PENTING
- Tulis **12 kata di atas kertas**. Simpan offline, BUKAN sebagai tangkapan layar.
- Frasa seed adalah **satu-satunya** cara memulihkan identitas dan dana dompet Anda. Hilang seed, hilang uang. Selamanya.
- Frasa ini memulihkan **identitas, dompet, dan dana** Anda — tetapi **bukan** reputasi, rating, riwayat transaksi, atau obrolan Anda, yang hanya tersimpan di perangkat ini dan tidak dapat dipulihkan.
- NEO-P2P **TIDAK PERNAH** meminta frasa seed Anda. Siapa pun yang memintanya adalah penipu.
- Centang tiga kotak konfirmasi, lalu verifikasi dengan memasukkan kata yang diminta.

### 3.4 Pulihkan (jika Anda sudah punya seed)
Di layar sambutan, ketuk **"Already have a seed phrase? Restore"** (Sudah punya frasa seed? Pulihkan) dan masukkan 12 kata Anda. Identitas dan dompet Anda pulih. Catatan: reputasi, rating, riwayat transaksi, dan transaksi yang sedang berjalan dari perangkat lama **tidak** ikut pindah — semuanya hanya tersimpan di perangkat tempat transaksi terjadi. Dana on-chain aman karena berasal dari seed.

---

## 4. Layar Utama (tab bawah)

| Tab | Fungsinya |
|-----|-----------|
| **Market** (Pasar) | Umpan penawaran langsung (penawaran jual dari semua rekan). Tarik untuk menyegarkan. |
| **Wallet** (Dompet) | Dompet Bitcoin HD pribadi Anda: saldo, terima, kirim, riwayat. |
| **Trades** (Transaksi) | Semua transaksi Anda: perlu tindakan, menunggu, selesai. Ketuk untuk masuk kembali ke transaksi. |
| **Profile** (Profil) | ID rekan, kunci publik, nama panggilan, reputasi, pintu masuk pengaturan. |

Bilah atas Market punya ikon **Chats** — semua percakapan Anda, terbaru lebih dulu, dengan lencana belum dibaca; chat transaksi yang sudah selesai tetap bisa dibaca di sana sebagai riwayat — plus ikon akses cepat untuk transaksi aktif Anda (chat + escrow). Bilah bawah punya 4 tab: Market, Wallet, Trades, Profile.

---

## 5. Menjual BTC (Buat Penawaran)

NEO-P2P **khusus jual** — Anda menerbitkan penawaran untuk menjual BTC; pembeli menemukan Anda di Market.

1. Ketuk **Create Offer** (Buat Penawaran) (FAB di Market, atau tombol saat layar kosong).
2. Isi:
   - **Amount (BTC)** (Jumlah) — berapa yang ingin Anda jual. Transaksi minimum setara **Rp 5.000.000**; maksimal 1 BTC.
   - **Price per BTC (IDR)** (Harga per BTC) — rupiah bulat saja (tanpa desimal).
   - **Valid for (TTL)** (Berlaku selama) — 6 jam / 12 jam / 24 jam / 48 jam / tanpa batas. Penawaran kedaluwarsa setelahnya.
   - **Payment methods** (Metode pembayaran) — pilih jenis (**Transfer Bank** atau **Dompet Digital**), lalu penyedianya, lalu masukkan **nomor rekening + nama pemilik rekening**. Transfer Bank: BCA, Mandiri, BNI, BRI, CIMB, Jago, SeaBank, BSI, BTN, Permata, Danamon, OCBC, Maybank. Dompet Digital: GoPay, OVO, Dana, ShopeePay, LinkAja. Detail ini tersimpan di perangkat Anda dan **tidak pernah dipublikasikan ke umpan publik** — detail dibagikan ke pembeli melalui chat terenkripsi hanya setelah escrow didanai.
3. Periksa **Fee Breakdown** (Rincian Biaya): jumlah transaksi, biaya penjual 0,5%, perkiraan biaya jaringan, total setoran.
4. **Publish Offer** (Terbitkan Penawaran). Penawaran Anda diumumkan ke jaringan dan muncul di Market semua orang.

**Mengelola penawaran Anda sendiri:**
- **Edit** (Ubah) — ganti harga/jumlah/metode (ada peringatan jika pembeli sudah menunggu). Penawaran terkunci (pembeli sudah cocok) **tidak bisa** diubah — ketentuannya adalah kesepakatan yang sedang berjalan.
- **Pause / Resume** (Jeda / Lanjutkan) — sembunyikan dari market sementara (hanya saat OPEN, tanpa taker aktif).
- **Delete** (Hapus) — permanen, tidak bisa dibatalkan, disiarkan ke semua rekan. Hanya bisa saat OPEN/PAUSED. Penawaran terkunci (pembeli sudah cocok) tidak bisa dihapus — selesaikan atau buka sengketa dulu.
- **Kedaluwarsa otomatis** — penawaran yang lewat masa berlakunya otomatis dihapus dari umpan. Jika pembeli menerima tetapi penjual tidak pernah membuat escrow, kecocokan otomatis dibatalkan setelah **1 jam** dan penawaran bisa diklaim lagi.

**Metode pembayaran tersimpan:** detail bank/e-wallet yang Anda masukkan tersimpan otomatis. Pengaturan → **My Payment Methods** (Metode Pembayaran Saya) untuk mengelolanya; penawaran baru terisi otomatis dari metode tersimpan.

---

## 6. Membeli BTC

1. Jelajahi **Market**. Filter berdasarkan min/maks IDR, urutkan berdasarkan terbaru atau hampir kedaluwarsa.
2. Ketuk penawaran → **Offer Details** (Detail Penawaran): jumlah, harga, total fiat, biaya, reputasi pedagang, metode pembayaran.
3. Ketuk **Accept Offer** (Terima Penawaran) → konfirmasi. Masukkan **alamat penerima BTC** Anda (tempat payout dikirim — `bc1…` di mainnet, `tb1…` di testnet).
4. Penawaran terkunci (MATCHED). Anda masuk ke **Trade Room** (Ruang Transaksi) — pusat transaksi ini dengan tab Escrow dan Chat.

**Yang terjadi selanjutnya (sudut pandang pembeli):**
- Penjual membuat escrow dan menyetor BTC. Anda menunggu (Anda **tidak** mengirim BTC — pembayaran Anda adalah transfer bank).
- Setelah didanai, detail rekening penjual tiba otomatis melalui chat terenkripsi.
- Anda transfer IDR, lalu kirim bukti pembayaran (lihat §8).

---

## 7. Alur Escrow (bagaimana uang tetap aman)

Escrow adalah **multisig 2-of-3 di blockchain**. Statusnya:

```
FUNDING → FUNDED → [SIGNED] → PAYMENT_PENDING → RECEIPT_SENT → CONFIRMING → RELEASED
   └→ CANCELLED (belum didanai, 30 menit)   └→ DISPUTED → RELEASED/REFUNDED
```

### Langkah penjual
1. **Danai escrow** — kirim `kripto + biaya 0,5% + biaya jaringan` ke alamat escrow.
   - **Satu ketukan:** "Send from my wallet to escrow" (Kirim dari dompet saya ke escrow). Aplikasi mengirim jumlah persis dari dompet Anda, mengisi txid otomatis, lalu memverifikasi on-chain. Tidak bisa dibatalkan — ada dialog konfirmasi dulu.
   - **Manual:** salin alamat escrow (Legacy `3…`/`2…` atau SegWit `bc1…`/`tb1…` — terkunci setelah didanai), kirim dari dompet mana pun, tempel txid, ketuk **Verify Deposit On-Chain** (Verifikasi Setoran On-Chain).
   - Pendanaan diverifikasi on-chain (default 1 konfirmasi). Menyetor **lebih** dari yang diminta? Kelebihannya kembali ke Anda saat payout/refund. Menyetor **kurang**? Setoran sebagian tetap dicatat — minta refund untuknya, lalu buat escrow baru (isi ulang tidak didukung).
   - **Request refund (Minta refund):** jika tidak ada setoran on-chain, **Request refund** membatalkan escrow secara lokal — tidak ada yang perlu di-refund, tidak ada pergerakan on-chain. Penawaran terkait ditandai CANCELLED dan pembeli diberi tahu. Jika setoran sudah dikirim, tunggu sampai terkonfirmasi lalu minta lagi: refund sekarang butuh **tanda tangan arbiter**, jadi akan membuka sengketa. **Tidak ada refund on-chain sepihak.**
2. **Bagikan detail pembayaran** — setelah didanai, chat terbuka. Ketuk **Share payment details** (Bagikan detail pembayaran) di chat untuk mengirim nomor rekening + nama pemilik sebagai kartu terenkripsi.
3. **Tunggu pembayaran + bukti dari pembeli.**
4. **Konfirmasi "IDR received"** (IDR diterima) — ini **satu-satunya gerbang pelepasan**. Saat uang benar-benar masuk rekening Anda, ketuk **IDR Received — Release** (IDR Diterima — Lepaskan). Payout ditandatangani dan disiarkan setelah konfirmasi Anda: BTC penuh → pembeli, 0,5% → dompet biaya.
   - **Tolak Bukti** — jika jumlah/nama salah atau tidak ada yang masuk, kirim penolakan dengan alasan (jumlah salah / nama tidak cocok / belum diterima / lainnya). Hanya bersifat informasi — dana tetap terkunci, status tidak berubah.

### Langkah pembeli
1. Tunggu pendanaan (Anda akan diberi tahu).
2. **Bayar persis jumlah yang ditampilkan** — 3 digit terakhir adalah **kode unik** untuk transaksi ini. Jumlah yang berbeda TIDAK akan dikenali penjual.
   - Gunakan hanya metode pembayaran yang dibagikan penjual. Transfer dari rekening **atas nama Anda sendiri**.
   - JANGAN menulis "crypto", "BTC", atau "NEO" di catatan transfer.
   - BI-FAST punya batas per bank — jumlah besar mungkin perlu RTGS.
3. Ketuk **I've Sent the Payment** (Saya Sudah Mengirim Pembayaran) (menandai PAYMENT_PENDING).
4. **Send Payment Receipt** (Kirim Bukti Pembayaran) — kode referensi (dibuat otomatis, bagikan ke penjual) + tangkapan layar transfer. Ini memindahkan transaksi ke RECEIPT_SENT.
5. Tunggu konfirmasi penjual. Jika penjual menolak, baca alasannya, perbaiki, kirim ulang.

### Batas waktu (pengaman otomatis)
| Situasi | Yang terjadi |
|---------|--------------|
| Escrow tidak didanai dalam **30 menit** | Dibatalkan otomatis |
| Sudah didanai tetapi macet **2 jam** (pengingat), lalu **+ tenggang 2 jam** | **Sengketa** otomatis — refund harus ditandatangani arbiter; tidak pernah di-refund otomatis |
| Pembeli sudah bayar tetapi penjual tidak konfirmasi dalam **1 jam + tenggang 1 jam** | **Sengketa** otomatis — tidak pernah di-refund diam-diam |

### Trade Room (Ruang Transaksi)
Pusat setelah penerimaan menampilkan: header status, pintasan langkah berikutnya yang menyesuaikan peran (Fund → Pay → Confirm → Release / Danai → Bayar → Konfirmasi → Lepaskan), detail escrow, dan chat. Tab Trades masuk kembali ke ruang ini untuk transaksi yang sedang berjalan.

---

## 8. Chat

- **E2EE:** setiap pesan terenkripsi end-to-end. Kunci diturunkan dari seed Anda; kunci rekan dipertukarkan melalui jabat tangan pre-key di atas LXMF.
- **Terkunci sampai didanai:** input chat baru terbuka setelah escrow FUNDED (detail rekening tidak pernah dikirim sebelum uang terkunci).
- **Sidik jari (TOFU):** **sidik jari BIP-39 8 kata** dari rekan Anda tampil di bar atas chat dan header escrow. Salin dan **bandingkan di luar aplikasi** (WhatsApp, telepon) sebelum transaksi besar — ini cara mendeteksi man-in-the-middle.
- **Antrean offline:** jika rekan offline, pesan mengantre dan terkirim otomatis saat mereka kembali.
- **Lampiran:** kirim file (mis. bukti pembayaran) sebagai lampiran terenkripsi.
- **Rating:** setelah transaksi selesai (RELEASED/REFUNDED), dialog "Rate your counterparty" (Beri rating rekan transaksi) muncul sekali. Rating bertanda tangan Anda dikirim ke rekan melalui LXMF terenkripsi dan memengaruhi skor reputasi kedua belah pihak.

---

## 9. Sengketa & Arbitrase

Ada yang salah? Penjual tidak pernah konfirmasi, pembeli tidak pernah bayar, bukti palsu — **buka sengketa**:

1. Layar Escrow → **Open Dispute** (Buka Sengketa) (tersedia dari FUNDED / PAYMENT_PENDING / RECEIPT_SENT untuk pembeli; penjual juga bisa membuka sengketa). Catatan: sengketa hanya bisa dibuka **setelah escrow didanai** — saat masih dalam pendanaan, jendela 30 menit membatalkannya otomatis.
2. Dana tetap **beku on-chain**. JANGAN kirim transfer lagi.
3. **Kirim bukti** — tangkapan layar bukti transfer bank + deskripsi (nama bank, jumlah, referensi). Referensi bukti terisi otomatis.
4. Arbiter (pemegang kunci ketiga) meninjau bukti dan menandatangani resolusi: **Release to Buyer** (Lepaskan ke Pembeli) atau **Refund to Seller** (Refund ke Penjual). Pihak yang menang menyiarkannya (2-of-3 lengkap).
5. Keputusan arbiter **mengikat** — bukti adalah satu-satunya hal yang diperhitungkan.

**Arbitrase dijalankan di luar aplikasi.** Arbiter mengoperasikan daemon headless terpisah (bukan aplikasi publik) yang menerima sengketa dan bukti lewat LXMF, meninjaunya, dan ikut menandatangani keputusan. Aplikasi hanya menerapkan resolusi yang ditandatangani — aplikasi tidak bisa mengarbitrase.

---

## 10. Dompet

- **Receive** (Terima) — alamat HD baru setiap kali (BIP-44, batas celah 20 alamat, tanpa pengulangan); QR + salin.
- **Send** (Kirim) — alamat tujuan (tempel atau pindai QR) dan jumlah dalam BTC. Biaya diperkirakan per tingkat (**cepat / sedang / lambat**) dan ditampilkan sebagai batas atas sebelum konfirmasi — aplikasi meminta Anda mengonfirmasi ulang daripada menyiarkan biaya yang lebih tinggi dari yang tampil. Koin dipilih otomatis (branch-and-bound, tanpa kembalian bila memungkinkan). Alamat Taproot (`bc1p…`) ditolak, karena model biaya payout escrow tidak mendukungnya.
- **Balance** (Saldo) — terkonfirmasi + belum terkonfirmasi, plus **Locked in escrow** (Terkunci di escrow) — dana yang tidak bisa Anda sentuh sampai transaksi selesai. Layar terbuka seketika dari snapshot tersimpan, lalu menyegarkan dari chain.
- **History** (Riwayat) — terkonfirmasi/menunggu, diterima/dikirim/sendiri.

---

## 11. Mengundang Rekan

Market → **Invite Peer** (Undang Rekan):
- **Show QR** (Tampilkan QR) — orang lain memindainya untuk menambahkan Anda.
- **Scan** (Pindai) — arahkan ke QR undangan mereka.
- **Paste** (Tempel) — tautan undangan (`neop2p://peer/<id>`) atau ID rekan mentah. Tautan juga bisa membawa fragmen identitas (`neop2p://peer/<id>#<hash>`); aplikasi menampilkannya agar Anda dapat memastikan kunci rekan yang benar.
- Tautan undangan juga berfungsi sebagai **deep link sistem** — ketuk tautan `neop2p://` di aplikasi mana pun untuk menambahkan rekan.

---

## 12. Pengaturan

| Bagian | Yang bisa Anda lakukan |
|--------|------------------------|
| **RNS Transport Node** (Node Transport RNS) | Lihat status koneksi; tambah/hapus **node transport ekstra** (host:port). Lebih banyak node = lebih banyak jangkauan, tidak pernah kurang aman — setiap node hanyalah feri paket. |
| **Language** (Bahasa) | Ikuti perangkat / Bahasa Indonesia / English (berlaku setelah restart). |
| **Enable Notifications (This Phone)** (Aktifkan Notifikasi (Ponsel Ini)) | Langkah khusus OEM (Xiaomi, Samsung, OPPO, Vivo, Huawei) agar ponsel tidak mematikan layanan P2P. **Lakukan ini** — jika tidak, Anda akan melewatkan pembayaran dan penawaran. |
| **My Payment Methods** (Metode Pembayaran Saya) | Kelola detail bank/e-wallet tersimpan. |
| **Reported Traders** (Pedagang Dilaporkan) | Laporan lokal saja (penipuan / pelecehan / bukti palsu / lainnya). Tidak pernah meninggalkan perangkat Anda, tidak pernah mengubah status transaksi. |
| **Blocked Traders** (Pedagang Diblokir) | Sembunyikan penawaran dari rekan tertentu (khusus perangkat). |
| **View Recovery Phrase** (Lihat Frasa Pemulihan) | Periksa ulang seed Anda (membutuhkan buka kunci perangkat). Jangan pernah membagikannya. |
| **Export / Import Identity** (Ekspor / Impor Identitas) | Cadangkan atau pulihkan identitas dan transaksi terbuka Anda ke file terenkripsi — dilindungi biometrik atau PIN perangkat Anda. |
| **Danger Zone — Destroy Local Trade Data** (Zona Bahaya — Hancurkan Data Transaksi Lokal) | Menghapus semua penawaran, transaksi, chat, metode tersimpan, dan reputasi di PERANGKAT INI. Identitas dan seed DI-PERTAHANKAN; dana on-chain tetap aman. Ketik **HAPUS** untuk konfirmasi. |
| **Danger Zone — Reset Identity** (Zona Bahaya — Atur Ulang Identitas) | Menghancurkan pasangan kunci Anda secara permanen. Anda kehilangan akses ke escrow aktif. Tidak bisa dibatalkan. |

---

## 13. Biaya (transparan, tanpa server)

- **0,5% dari transaksi, dibayar penjual saja.** Pembeli tidak membayar apa pun dan menerima BTC penuh.
- Alamat dompet biaya **tertanam di aplikasi open-source** — verifikasi di kode sebelum mempercayai build mana pun. Aplikasi juga **menolak payout apa pun yang mengirim sats pembeli ke dompet biaya atau kembali ke escrow itu sendiri** (2026-09-07).
- Biaya jaringan (miner) diperkirakan secara dinamis dan ditampilkan sebelum Anda mengonfirmasi transaksi apa pun.

---

## 14. Pemecahan Masalah

| Masalah | Solusi |
|---------|--------|
| "P2P transport is offline" (Transport P2P offline) | Periksa internet. Ketuk Retry (Coba Lagi). Aplikasi terhubung ulang otomatis (setiap 60 detik di latar depan, setiap 5 menit saat di latar belakang). Banner + notifikasi transport mati juga muncul saat node tidak terjangkau. |
| Notifikasi "Identity locked" (Identitas terkunci) | Buka kunci layar perangkat — aplikasi melanjutkan P2P otomatis. |
| Tidak ada penawaran yang tampil | Tarik untuk menyegarkan (mengumumkan ulang umpan Anda). Penawaran bersifat sementara — rekan yang bergabung sebelum penawaran diumumkan perlu menyegarkan. |
| Rekan tidak terjangkau / pesan mengantre | Rekan offline. Pesan mengantre dan terkirim saat mereka kembali (store-and-forward LXMF). |
| Notifikasi terlewat | Pengaturan → Enable Notifications (This Phone) dan ikuti langkah OEM. |
| Koneksi lambat/relay | Anda terhubung melalui node transport. Dana tetap aman on-chain; sinkronisasi transaksi mungkin tertunda. |
| "Offer taken" (Penawaran diambil) saat menerima | Pembeli lain lebih dulu mengambilnya. Pilih penawaran lain. |
| Tidak bisa menghapus penawaran | Penawaran terkunci (pembeli cocok atau escrow aktif). Selesaikan atau buka sengketa dulu. |
| Tidak bisa mengubah penawaran | Terkunci (pembeli cocok) — ketentuannya adalah kesepakatan yang sedang berjalan. |
| Penawaran yang cocok menghilang | Pembeli menerima tetapi tidak ada escrow dibuat dalam 1 jam — kecocokan dibatalkan otomatis dan penawaran bisa diklaim lagi. |
| Jumlah pembayaran salah | 3 digit terakhir adalah kode unik — transfer TOTAL persis yang ditampilkan. |
| Pencarian data on-chain macet / saldo atau pendanaan tidak diperbarui (Indonesia) | Sebagian ISP — terutama **Telkomsel seluler** — memblokir atau melakukan TLS-intercept pada domain explorer on-chain. Aplikasi otomatis merotasi beberapa penyedia yang gagal-tertutup (mainnet: `mempool.space` → `blockstream.info` → `mempool.emzy.de` → `btcscan.org` → `blockchain.com`; testnet4: `mempool.emzy.de` untuk tip/biaya, `mempool.space` untuk alamat), jadi penyedia utama yang diblokir hanya memakan satu percobaan gagal sebelum beralih. Jika tetap gagal atau lambat, aktifkan aplikasi **Cloudflare 1.1.1.1 (One Dot One)** dengan **WARP** — [Play Store](https://play.google.com/store/apps/details?id=com.cloudflare.onedotonedotone&pcampaignid=web_share) — atau **ProtonVPN** — [Play Store](https://play.google.com/store/apps/details?id=ch.protonvpn.android&referrer=utm_source%3Dprotonvpn.com%26utm_medium%3Dweb%26utm_campaign%3Dpvpn_all_auto) — atau VPN apa pun, lalu ketuk Coba Lagi. Mengganti DNS saja tidak cukup (blokirnya di level TLS/SNI). |

---

## 15. Daftar Periksa Keamanan (baca ini)

1. **Frasa seed:** kertas, offline, jangan pernah diketik ke situs web atau aplikasi mana pun. NEO-P2P tidak pernah memintanya.
2. **Sidik jari:** bandingkan sidik jari 8 kata rekan di luar aplikasi sebelum melepaskan atau membayar jumlah besar.
3. **Lepaskan hanya saat uang ADA di rekening ANDA** — tangkapan layar bukan uang. Bukti transfer palsu adalah penipuan #1.
4. **Transfer atas nama sendiri saja** — transfer dari rekening pihak ketiga adalah tanda bahaya dan sulit dibuktikan.
5. **Tanpa kata kripto di catatan transfer** — bank membekukan rekening untuk transfer terkait kripto.
6. **Buka sengketa lebih awal, bukan terlambat** — jika rekan macet, buka sengketa selagi bukti masih segar.
7. **Ini mainnet** — nilai nyata. Perlakukan sebagai uang sungguhan.

---

## 16. Keterbatasan yang Diketahui

- Chat E2EE adalah E2EE v2 (double ratchet) — forward secrecy + post-compromise security, hanya bisa saling terhubung antar rekan NEO-P2P (bukan NIP-44/59), kepercayaan kunci TOFU (diminimalkan dengan sidik jari + pengikatan identitas saat kontak pertama). Kedua pihak harus memakai v0.1.1+ — bundel v1 lama ditolak.
- Harga market berasal dari CoinGecko lalu CoinPaprika (keduanya langsung IDR), di-cache 5 menit; jika keduanya tidak terjangkau, kolom harga dibiarkan kosong daripada diisi nilai lama yang tidak akurat.
- Node transport adalah titik kegagalan tunggal untuk rekan internet (diminimalkan dengan penemuan LAN + node ekstra).

---

*NEO-P2P adalah perangkat lunak eksperimental, disediakan "apa adanya". Trading kripto membawa risiko finansial. Gunakan dengan risiko Anda sendiri.*
