# 01 — Analisis Konten

**Sumber:** manual/USER_MANUAL.md (EN, v1.0.29)
**Target:** Bahasa Indonesia, audiens umum (pengguna akhir aplikasi, bukan pengembang)

## Domain
Manual pengguna aplikasi jual-beli Bitcoin P2P anonim untuk Indonesia. Topik: onboarding/seed phrase, penawaran jual, escrow multisig 2-of-3, chat E2EE, sengketa/arbitrase, dompet, pengaturan, keamanan.

## Nada
Instruktif, tegas, ramah. Banyak peringatan (⚠️) dan penekanan (bold) pada langkah kritis: cadangan seed, kode unik, gerbang pelepasan, peringatan mainnet.

## Terminologi kunci (glosarium sesi)
| EN | ID | Catatan |
|----|----|---------|
| seed phrase | frasa seed (12 kata) | istilah umum di komunitas kripto ID; pertahankan "seed" |
| escrow | escrow | istilah baku; penjelasan "dana ditahan" di konteks |
| multisig 2-of-3 | multisig 2-of-3 | pertahankan |
| offer | penawaran | |
| fee | biaya | |
| wallet | dompet | |
| payout | payout / pencairan | pertahankan "payout" (istilah umum) |
| release gate | gerbang pelepasan | |
| peer | rekan (transaksi) | |
| fingerprint (TOFU) | sidik jari (TOFU) | |
| kode unik | kode unik | sudah bahasa Indonesia |
| Tolak Bukti | Tolak Bukti | string UI asli ID |
| HAPUS | HAPUS | string konfirmasi asli |
| mainnet / testnet | mainnet / testnet | pertahankan |
| MATCHED, FUNDING, FUNDED, dll. | pertahankan (token status UI) | jelaskan dalam bahasa Indonesia |
| chargeback | chargeback | |
| frozen account | rekening dibekukan | |
| TTL | masa berlaku | |
| UTXO | UTXO | pertahankan |
| RTGS / BI-FAST | RTGS / BI-FAST | istilah perbankan ID |

## Tantangan penerjemahan
1. Status escrow (FUNDING → RELEASED) adalah token UI — pertahankan huruf kapital EN, jelaskan konteksnya dalam ID.
2. Nama tombol UI (mis. "Send from my wallet to escrow") — pertahankan label EN asli + terjemahan dalam kurung, karena pengguna melihat label EN di aplikasi.
3. Istilah keuangan harus presisi: 0,5% (koma desimal ID), Rp 5.000.000, "3 digit terakhir = kode unik".
4. Nada peringatan harus sama kuatnya — jangan melunakkan.
5. Gaya storytelling per EXTEND.md, tetapi manual = kejelasan di atas segalanya; kalimat pendek, langsung.
