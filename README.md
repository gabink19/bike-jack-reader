# Bike Jack Reader

Aplikasi Android untuk membaca sensor sepeda statis lewat jack 3.5mm, lalu menampilkan
kecepatan, RPM kayuhan, jarak, waktu, kalori (perkiraan), rata-rata, dan kecepatan maksimum.

## Cara kerjanya

Jack 3.5mm mono (TS, 1 ring) pada sepeda statis hampir selalu berupa **kabel sensor reed switch**:
sebuah magnet di roda gila/crank melewati saklar, saklar menutup sesaat setiap putaran, dan
console menghitung pulsa itu. Console tidak mengirim data digital ke luar lewat jack ini.

Aplikasi ini membaca saklar yang sama melalui **input mikrofon** HP. Input mic menyediakan
tegangan bias kecil (~2 V). Saat saklar menutup/membuka, tegangannya berubah dan muncul sebagai
lonjakan di sinyal audio. Aplikasi menghitung lonjakan itu menjadi putaran.

## Kabel yang perlu dibuat

Colokan HP adalah TRRS (4 kutub, standar CTIA, dipakai hampir semua HP):

```
TRRS HP:   Tip = L   Ring1 = R   Ring2 = GND   Sleeve = MIC
TS sensor: Tip = sinyal          Sleeve = GND
```

Rangkaian (resistor 1/4 W biasa):

```
 MIC (sleeve TRRS) ──┬──[ R1 2.2kΩ ]──────────────┬── GND (ring2 TRRS)
                     │                            │
                     └──[ R2 2.2kΩ ]── reed ──────┘
                                  (tip & sleeve colokan TS sensor)
```

- **R1** membuat HP mengenali ada "mikrofon" (tanpa ini banyak HP mengira yang dicolok
  headphone biasa tanpa mic, lalu tetap memakai mic internal).
- **R2** menjaga hambatan saat saklar menutup tetap ~1.1 kΩ, sehingga HP **tidak** salah
  mengira ini tombol play/pause headset (tombol headset < ~700 Ω).

Cara termudah: beli **jack female 3.5mm mono** (untuk colokan sensor) + **kabel/plug TRRS**
(atau potong kabel headset bekas yang ada mic-nya), lalu solder sesuai gambar.

HP tanpa jack 3.5mm: pakai adaptor **USB-C ke 3.5mm yang mendukung mic** (adaptor DAC
biasanya mendukung). Aplikasi otomatis memilih input USB.

**Ingin console sepeda tetap menyala bersamaan?** Pakai splitter mono: sensor → splitter →
(1) console, (2) kabel di atas. Jika console jadi tidak stabil, tambahkan kapasitor 1–10 µF
seri di antara titik sensor dan R2.

## Sumber input

Di bagian atas layar ada pilihan **Sumber input**:

| Pilihan | Dipakai untuk |
| --- | --- |
| Otomatis | Jack 3.5mm jika terdeteksi, lalu USB-C, jika tidak ada pakai default sistem |
| Jack | Memaksa input dari jack 3.5mm |
| USB-C | Memaksa input dari adaptor USB-C ke 3.5mm |
| Mic HP | Mic bawaan HP, untuk uji coba tanpa kabel (ketuk-ketuk dekat mic) |

Status menampilkan perangkat yang **benar-benar** dipakai Android. Jika tertulis
"... tidak terdeteksi", HP tidak mengenali perangkat itu sebagai input mic (cek R1).
Jika tertulis "permintaan ... diabaikan sistem", HP memaksa rute lain; ini keterbatasan
sebagian HP, coba pilihan lain atau cabut kabel lain yang terpasang.

## Kalibrasi di aplikasi

1. Colok kabel, buka aplikasi, izinkan mikrofon. Status harus menampilkan `Input: Jack 3.5mm`
   (atau USB-C). Kalau `Mic HP`, HP belum mengenali kabel (cek R1).
2. Buka **Pengaturan & kalibrasi**, kayuh pelan. Grafik harus menunjukkan lonjakan tiap putaran
   dan titik hijau berkedip **satu kali per putaran**.
   - Tidak berkedip → turunkan *Ambang deteksi*.
   - Berkedip 2x per putaran → pilih polaritas *Positif* atau *Negatif*.
   - Berkedip acak saat diam → naikkan ambang.
3. Samakan dengan console: kayuh beberapa menit, bandingkan jarak.
   `Jarak per putaran baru = lama × (jarak console ÷ jarak aplikasi)`.
4. Jika sensor punya lebih dari satu magnet, isi *Pulsa per putaran*.

Kalori dihitung dengan rumus MET berdasarkan kecepatan dan berat badan; ini perkiraan kasar.

## Update dari dalam aplikasi

Aplikasi mengecek GitHub Releases saat dibuka (paling sering tiap 6 jam), atau manual lewat
**Pengaturan → Cek update**. Jika ada release lebih baru, muncul tombol hijau
**Update tersedia**: ketuk, APK diunduh, lalu layar pemasangan Android terbuka.

- Pertama kali, Android meminta izin **Instal aplikasi tidak dikenal** untuk Bike Jack Reader.
  Izinkan, kembali ke aplikasi, lalu ketuk Update lagi.
- Hanya **release** (tag `v*`) yang ditawarkan, bukan build biasa dari `main`.
- Update hanya bisa menimpa versi terpasang jika ditandatangani kunci yang sama
  (`bike.jks` di Secrets). Jangan ganti keystore.
- Repo harus **public**, karena aplikasi mengakses GitHub tanpa login.

## Build lewat GitHub

Workflow ada di `.github/workflows/android.yml`:

- Setiap push ke `main` (atau *Run workflow* manual): unit test → build APK →
  unduh di tab **Actions** → run terbaru → **Artifacts** → `BikeJackReader-apk`.
- Push tag `v*` (misal `git tag v1.0 && git push origin v1.0`): APK juga dilampirkan ke
  **Releases**.

### Tanda tangan APK (disarankan)

Tanpa secret, APK ditandatangani debug key sementara yang berubah tiap build, jadi update
harus uninstall dulu (data pengaturan hilang). Untuk kunci tetap, buat keystore sekali:

```bash
keytool -genkeypair -v -keystore bike.jks -alias bike -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 bike.jks   # Windows PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("bike.jks"))
```

Lalu tambahkan di GitHub → Settings → Secrets and variables → Actions:

| Secret | Isi |
| --- | --- |
| `BIKE_KEYSTORE_BASE64` | hasil base64 file `bike.jks` |
| `BIKE_KEYSTORE_PASSWORD` | password keystore (dipakai juga sebagai password key) |
| `BIKE_KEY_ALIAS` | `bike` (opsional, default `bike`) |

Simpan `bike.jks` di tempat aman dan jangan di-commit.
