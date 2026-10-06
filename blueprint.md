# Blueprint — App Android "Zora Drone Controller"

Status: **rencana**, belum ada kode. Semua detail protokol di bawah sudah dicek langsung dari firmware drone (file:baris disebut).
Path file firmware merujuk ke repo firmware `esp-drone` (folder sebelah: `../esp-drone`, branch `zora-s2mini`).

## 1. Kenapa perlu app sendiri

- App resmi ESP-Drone Android (2020, `https://www.pgyer.com/a27L`) **tidak mengirim paket** di tablet Infinix XPAD 20 Pro (Android 14/15).
  Bukti dari log: tablet join AP (`station ... join`), tapi tidak ada paket UDP yang sampai (tidak ada `udp packet cksum unmatched`, LED tetap kedip lambat).
- Dugaan kuat: Android 10+ tidak memakai WiFi "tanpa internet" sebagai jalur default. App lama tidak memanggil `bindProcessToNetwork`, jadi paketnya tidak pernah keluar lewat WiFi drone.
- iOS tidak punya batasan ini, jadi app iOS bisa jalan.
- Bonus: app sendiri bisa jadi fondasi integrasi Zora (kontrol suara) nanti.

## 2. Scope

**MVP (wajib):**
- Connect ke AP drone, ikat (bind) socket ke jaringan WiFi itu.
- Dua joystick virtual: kiri = thrust (atas-bawah) + yaw (kiri-kanan), kanan = pitch (atas-bawah) + roll (kiri-kanan). Mode 2, layout standar RC.
- Kirim setpoint 50 Hz.
- Tombol **STOP** besar (thrust 0, tahan terus).
- Indikator koneksi (ada/tidaknya paket balasan dari drone).

**Fase 2:**
- Telemetri baterai (`pm.vbat`) lewat CRTP log.
- Trim roll/pitch, sensitivitas, expo.
- Simpan setting.
- **Kalibrasi level + telemetri** (bagian 12): tampilkan kemiringan roll/pitch & baterai, tombol "SET LEVEL 0°".
- **Mode Test Motor** (bagian 11): putar M1–M4 satu per satu, atau keempatnya bersama dengan slider/ramp thrust. Untuk bench test tanpa propeller.

**Di luar scope sekarang:** altitude/position hold (butuh sensor tambahan), FPV, integrasi Zora.

## 3. Protokol (dari firmware)

### Jaringan

| Item | Nilai | Sumber |
|---|---|---|
| SSID | `ESP-DRONE_<MAC>` (contoh `ESP-DRONE_48F6EE79C585`) | `sdkconfig` `CONFIG_WIFI_BASE_SSID` |
| Password | `12345678` | log `wifi_init_softap` |
| IP drone | `192.168.43.42` | `wifi_esp32.c:308` |
| Port UDP | `2390` (drone listen) | `wifi_esp32.c:29` |
| Balasan | dikirim ke **IP:port asal** paket terakhir | `wifi_esp32.c:189` (`source_addr`) |

App cukup membuka 1 socket UDP (port lokal bebas), lalu kirim ke `192.168.43.42:2390` dan baca balasan dari socket yang sama.

### Format paket (app → drone)

Setiap datagram UDP berisi **paket CRTP + 1 byte checksum**:

```
offset  size  isi
0       1     header CRTP = 0x30   (port 3 = commander, channel 0)
1       4     roll    float32 LE   derajat, sudut absolut (mode ANGLE)
5       4     pitch   float32 LE   derajat, sudut absolut (mode ANGLE)
9       4     yaw     float32 LE   derajat/detik (mode RATE)
13      2     thrust  uint16  LE   0..60000
15      1     checksum = (jumlah byte 0..14) & 0xFF
```

Total **16 byte**. Sumber: struct `CommanderCrtpLegacyValues` di `crtp_commander_rpyt.c:48`, checksum di `wifi_esp32.c:54`.
Paket dengan checksum salah dibuang, dan firmware mencetak `udp packet cksum unmatched` di log.

### Aturan perilaku firmware yang wajib diikuti app

| Aturan | Detail | Sumber |
|---|---|---|
| **Thrust lock** | Setelah connect, thrust diabaikan sampai app **pernah mengirim thrust = 0**. | `crtp_commander_rpyt.c:166-180` |
| Thrust minimum | thrust < 1000 dianggap 0 | `MIN_THRUST` |
| Thrust maksimum | dipotong di `MAX_THRUST` = 60000 (stock) | `crtp_commander_rpyt.c:42` |
| Watchdog | Tidak ada setpoint > **500 ms**: drone diratakan. > **2000 ms**: motor mati. | `commander.h:35-36` |
| "Connected" | Drone menganggap link hidup kalau ada paket < **1000 ms** terakhir (LED biru nyala terus) | `wifilink.c:49` |
| Roll/pitch | sudut absolut (derajat), default ANGLE | `crtp_commander_rpyt.c:75-76` |
| Yaw | kecepatan putar (derajat/detik), default RATE | `crtp_commander_rpyt.c:77` |
| Emergency stop | kalau drone terbalik, motor mati sampai reboot (LED kedip sangat cepat) | `sitaw.c:144` |

Konsekuensi untuk app:
- Kirim **50 Hz** (tiap 20 ms). Jauh di bawah batas watchdog, dan cukup halus.
- Paket pertama setelah connect **selalu thrust 0**.
- Kalau app ke background, layar mati, atau joystick dilepas: **kirim thrust 0** beberapa kali, lalu berhenti kirim.

### Paket balasan (drone → app)

Drone membalas dengan paket CRTP (+ checksum) ke alamat asal. MVP cukup memakai "ada paket balasan dalam 1 detik terakhir" sebagai indikator connected.
Fase 2: parsing CRTP log (port 5) untuk membaca `pm.vbat`. Ini butuh download TOC (daftar variabel log), lihat referensi cflib di bagian 9.

## 4. Pemetaan joystick → setpoint

| Input | Range joystick | Setpoint | Default |
|---|---|---|---|
| Kiri vertikal | 0..1 (bawah = 0) | thrust | `0..MAX_THRUST_APP` (60000) |
| Kiri horizontal | -1..1 | yaw rate | ±150 °/s |
| Kanan vertikal | -1..1 | pitch | ±15° (atas = maju) |
| Kanan horizontal | -1..1 | roll | ±15° (kanan = kanan) |

- **Deadzone** 5% di tengah tiap sumbu, supaya drone tidak "melorot" karena jari tidak pas di tengah.
- **Expo** (opsional): `out = x³·e + x·(1-e)`, e = 0.3. Halus di tengah, tetap penuh di ujung.
- **Thrust kiri tidak auto-center** (seperti stik RC throttle). Sumbu lain kembali ke tengah saat dilepas.
- **Tanda pitch:** cek di bench. Kalau stik maju membuat motor belakang yang naik, tanda pitch dibalik. Firmware Crazyflie: pitch positif = hidung naik.

## 5. Arsitektur app

**Stack:** Kotlin, Jetpack Compose, coroutines. `minSdk 29` (Android 10), `targetSdk` terbaru. Single activity, tanpa library pihak ketiga.

```
app/
 ├─ MainActivity.kt           // host Compose, minta permission, layar selalu nyala saat terbang
 ├─ net/DroneNetwork.kt       // cari & bind jaringan WiFi drone (bagian 6)
 ├─ net/DroneLink.kt          // DatagramSocket: send(), receive loop, status connected
 ├─ proto/Crtp.kt             // encodeSetpoint(roll,pitch,yaw,thrust): ByteArray + checksum
 ├─ control/ControlLoop.kt    // coroutine 50 Hz: baca state joystick → encode → send
 ├─ control/ControlState.kt   // StateFlow joystick + flag armed/stop
 └─ ui/
     ├─ FlyScreen.kt          // 2 joystick + STOP + status bar
     └─ Joystick.kt           // komponen joystick (pointerInput + drag)
```

Alur data:
```
Joystick (UI) ─► ControlState (StateFlow) ─► ControlLoop 50 Hz ─► Crtp.encode ─► DroneLink.send ─► UDP
                                                                             DroneLink.receive ─► status "connected"
```

## 6. Ikat socket ke WiFi drone (bagian paling penting)

Inilah yang membuat app lama gagal. Dua opsi:

**Opsi A — user sudah connect manual ke WiFi drone (paling simpel, untuk MVP):**
```kotlin
val cm = getSystemService(ConnectivityManager::class.java)
val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // AP drone tidak punya internet
    .build()
cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        cm.bindProcessToNetwork(network)   // semua socket proses ini lewat WiFi drone
        // atau: network.bindSocket(datagramSocket)
    }
})
```

**Opsi B — app yang menyambungkan ke AP (Android 10+, `WifiNetworkSpecifier`):**
`setSsidPattern(PatternMatcher("ESP-DRONE_", PATTERN_PREFIX))` + `setWpa2Passphrase("12345678")`, lalu `requestNetwork` dan `bindProcessToNetwork` di `onAvailable`. Android akan menampilkan dialog pilih jaringan. UX lebih enak, tapi lebih banyak kasus tepi. Kerjakan setelah MVP.

**Permission (AndroidManifest):**
`INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`.
Opsi B di Android 13+ juga butuh `NEARBY_WIFI_DEVICES` (runtime permission).

**Cek cepat bahwa binding berhasil:** LED biru drone berubah dari kedip lambat menjadi **nyala terus** dalam 1 detik setelah app mulai mengirim.

## 7. Keselamatan (wajib di MVP)

- Paket pertama dan saat "disarm": **thrust 0**.
- **Tombol STOP** besar dan selalu terlihat: set thrust 0, kunci thrust sampai joystick kiri diturunkan ke bawah lagi.
- `onPause` / `onStop` / layar mati / kehilangan jaringan: kirim thrust 0 lima kali, lalu stop loop.
- **Arm switch:** thrust tidak dikirim (> 0) sebelum user menggeser toggle "ARM". Mencegah motor nyala karena jari menyenggol layar.
- `FLAG_KEEP_SCREEN_ON` saat di layar terbang.
- Batas thrust app (`MAX_THRUST_APP`) bisa diatur, default 60000 (sama dengan firmware).
- **Semua tes awal tanpa propeller.**

## 8. Milestone & cara uji

| # | Target | Selesai kalau |
|---|---|---|
| M0 | Project Kotlin + Compose kosong jalan di tablet | app terbuka |
| M1 | `DroneNetwork` + `DroneLink` + kirim paket thrust 0 tiap 20 ms | **LED drone nyala terus** |
| M2 | Slider thrust sederhana (tanpa joystick) | motor berputar, naik-turun mengikuti slider (tanpa prop) |
| M3 | 2 joystick + mapping + deadzone | dari log `ATT ... pwm=` (debug ON): pwm berubah sesuai arah stik |
| M4 | Keselamatan: ARM, STOP, lifecycle → thrust 0 | lepas app/kunci layar, motor berhenti < 0,5 s |
| M5 | Tes terbang pendek dengan prop, area lapang | hover terkendali |
| F2 | Telemetri baterai, trim, expo, simpan setting | angka baterai tampil di app |

**Unit test kecil (wajib, tanpa framework tambahan selain JUnit bawaan):**
`Crtp.encodeSetpoint(0f, 0f, 0f, 0)` harus menghasilkan 16 byte, byte[0] = `0x30`, byte[15] = jumlah byte 0..14 & 0xFF.
Bandingkan dengan satu paket yang dihitung manual.

## 9. Referensi

- Firmware commander: `components/core/crazyflie/modules/src/crtp_commander_rpyt.c`
- UDP server & checksum: `components/drivers/general/wifi/wifi_esp32.c`
- Link timeout: `components/core/crazyflie/hal/src/wifilink.c`
- Protokol CRTP & log/param: `../esp-drone/docs/en/rst/communication.rst`
- App iOS resmi (pembanding perilaku): `https://github.com/EspressifApps/ESP-Drone-iOS`
- Source app Android lama (referensi parsing CRTP log untuk fase 2): `https://github.com/EspressifApps/ESP-Drone-Android`

## 10. Prasyarat drone sebelum M5 (terbang dengan prop)

- [ ] SS14 + elco di jalur VBUS (cegah brownout, lihat `../esp-drone/docs/WIRING.md`)
- [ ] Kalibrasi level `ROLL_CALIB` / `PITCH_CALIB`
- [ ] Baterai 1S dengan rating C memadai (≥ 20C)
- [ ] Arah putar & propeller sesuai `../esp-drone/docs/WIRING.md` §7

## 11. Mode Test Motor (bench test)

**Tujuan:** cek wiring tiap kanal motor dan batas baterai dari app, tanpa flash firmware tes (`tools/motortest`, `tools/thrusttest`).
**Bisa dilakukan tanpa ubah protokol:** firmware sudah punya parameter `motorPowerSet` yang langsung menulis PWM motor dan **melewati stabilizer**.

### Parameter firmware (dicek di source)

| Param | Tipe | Arti | Sumber |
|---|---|---|---|
| `motorPowerSet.enable` | uint8 | `1` = motor ikut nilai m1..m4 di bawah, stabilizer diabaikan. `0` = normal | `power_distribution_stock.c:135-141` |
| `motorPowerSet.m1` .. `m4` | uint16 | PWM tiap motor, 0..65535 | idem |

`powerDistribution()` memakai nilai ini selama `enable = 1` (`power_distribution_stock.c:106-112`), asal drone tidak dalam emergency stop.

### Cara set parameter tanpa download TOC: "set by name"

Firmware menerima perintah set-by-name di **CRTP port 2 (param), channel 3 (misc)**, `param.c:181-207`:

```
byte 0      header CRTP = 0x23            (port 2 << 4 | channel 3)
byte 1      0x00                          (MISC_SETBYNAME, param.c:75)
byte 2..    "motorPowerSet\0"             (nama grup, diakhiri 0)
            "m1\0"                        (nama param, diakhiri 0)
            tipe: 0x08 = uint8, 0x09 = uint16   (param.h:157-159, harus sama persis)
            nilai little-endian (1 byte untuk uint8, 2 byte untuk uint16)
terakhir    checksum UDP (jumlah byte & 0xFF), sama seperti setpoint
```

Contoh: set `m1 = 20000` → `23 00 "motorPowerSet" 00 "m1" 00 09 20 4E` + checksum.
Drone membalas paket yang sama dengan byte tipe diganti **kode error** (0 = OK, `ENOENT` = nama salah, `EINVAL` = tipe salah).
Panjang maksimal paket CRTP 30 byte, nama ini muat (±25 byte).

### UI

- Layar terpisah **"Test Motor"**, hanya bisa dibuka saat ARM **mati**. Banner merah: **"LEPAS PROPELLER"**.
- 4 tombol tahan-untuk-putar: **M1, M2, M3, M4**. Selama ditekan, motor itu jalan di nilai slider. Dilepas → 0.
- Tombol **SEMUA**: keempat motor bersama di nilai slider.
- Slider PWM 0–100% (map ke 0–65535), default 20%.
- Tombol **RAMP**: keempat motor naik 10% → 100%, +5% tiap 2 detik (sama seperti `thrusttest`), berhenti otomatis atau saat STOP.
- Label posisi motor (M1 depan-kanan, M2 belakang-kanan, M3 belakang-kiri, M4 depan-kiri) dan warna kabel (PH/MB) supaya mudah dicocokkan.
- Kalau telemetri baterai (fase 2) sudah ada: tampilkan `vbat` saat ramp untuk melihat seberapa dalam baterai anjlok.

### Alur paket

1. Masuk mode: set `m1..m4 = 0` dulu, lalu `enable = 1`.
2. Selama mode aktif: kirim ulang nilai m1..m4 tiap 100 ms (paket UDP bisa hilang), dan tetap kirim echo untuk status koneksi.
3. Keluar mode / STOP / app ke background / koneksi putus: set `m1..m4 = 0`, lalu `enable = 0`. Kirim 3 kali.

### Pengaman di firmware (sudah ada, 2026-10-06)

`motorPowerSet` aslinya **tidak punya watchdog**: kalau app tertutup atau WiFi putus saat `enable = 1`, motor terus berputar di nilai terakhir.
Firmware `esp-drone` (`power_distribution_stock.c`, `powerDistribution()`) sekarang memaksa `enable = 0` dan m1..m4 = 0
kalau **tidak ada paket dari app > 1 detik** (`crtpIsConnected()`, `WIFI_ACTIVITY_TIMEOUT_MS`). Log: `motorPowerSet disabled: link lost`.
Konsekuensi untuk app: selama mode test aktif, **terus kirim paket** (echo atau set ulang m1..m4) minimal tiap < 1 detik.

### Uji

| Langkah | Selesai kalau |
|---|---|
| Tahan M1 di 30% | hanya motor depan-kanan yang berputar |
| Ulangi M2, M3, M4 | tiap tombol cocok dengan posisinya |
| SEMUA di 50% | keempat motor berputar, LED drone tetap nyala terus |
| Matikan WiFi tablet saat SEMUA aktif | motor berhenti < 1,5 detik (pengaman firmware) |
| RAMP sampai 100% pakai baterai saja | catat persen saat ESP reboot (kalau ada) |

## 12. Kalibrasi level & telemetri (firmware sudah siap, 2026-10-06)

**Masalah yang diselesaikan:** sensor membaca drone miring beberapa derajat walau di lantai datar (contoh terukur: pitch −5,3°).
Stabilizer lalu "meratakan" yang sebenarnya sudah rata → motor depan kencang, motor belakang hampir mati (gejala "cuma 2 baling yang muter").
Dulu offset hanya bisa diubah lewat `ROLL_CALIB`/`PITCH_CALIB` di menuconfig + flash ulang. Sekarang bisa dari app.

### Firmware (esp-drone, belum di-commit)

| Bagian | Detail | File |
|---|---|---|
| Param `levelCal.trigger` (uint8) | tulis **1** = rata-rata accelerometer ±0,5 s, jadikan pose sekarang 0°/0°, simpan ke NVS. Tulis **2** = hapus kalibrasi (0°/0°). Kembali 0 sendiri setelah selesai | `sensors_mpu6050_hm5883L_ms5611.c` (`levelCalSample`) |
| Param `levelCal.roll` / `levelCal.pitch` (float, read-only) | nilai kalibrasi aktif (derajat) | idem |
| Simpan permanen | NVS namespace `zora`, key `calR`/`calP` (derajat × 100). Saat boot: NVS kalau ada, kalau tidak pakai Kconfig `ROLL_CALIB`/`PITCH_CALIB` | idem (`levelCalLoad`) |
| Telemetri 10 Hz | dikirim selama app terhubung (ada paket < 1 s) | `main/main.c` (`telemetryTask`) |

Set trigger pakai set-by-name yang sama dengan mode test motor (bagian 11):
`Crtp.setParam("levelCal", "trigger", 1, uint16 = false)` → `23 00 "levelCal" 00 "trigger" 00 08 01` + checksum.

### Format paket telemetri (drone → app)

```
byte 0      header CRTP = 0xA0         (port 0x0A, channel 0; port ini tidak dipakai Crazyflie)
             cek pakai (b[0] and 0xF3) == 0xA0, sama seperti echo
byte 1..24  6 × float32 little-endian:
              roll, pitch, yaw   (derajat, hasil estimator — yang dipakai stabilizer)
              vbat               (volt, divider GPIO2)
              calRoll, calPitch  (derajat, kalibrasi level aktif)
byte 25     checksum UDP (jumlah byte 0..24 & 0xFF)
```
Total 26 byte. Konvensi tanda (diverifikasi dengan tes tekan sudut): **roll + = sisi kanan turun**, **pitch − = hidung turun**.

### UI

- Status bar: **Tilt** `R x.x° P y.y°` (hijau kalau keduanya < 2°) dan **Batt** `x.xx V` (oranye < 3,5 V).
- Tombol **"SET LEVEL 0°"**: aktif hanya saat **ARM mati** dan drone **connected**. Klik → dialog konfirmasi:
  "Taruh drone di permukaan datar dengan posisi seperti saat terbang, jangan disentuh. Posisi sekarang dijadikan 0° dan disimpan permanen di drone."
  Tampilkan juga nilai sekarang dan kalibrasi lama (`calRoll`/`calPitch`). Konfirmasi → kirim trigger 1 (2× dengan jeda 20 ms, aman: firmware mengabaikan tulis ulang selama sampling).
- Setelah ±1 detik, Tilt harus menunjukkan ≈ 0°/0°. Kalau tidak, ulangi.
- Opsional: tombol kecil "hapus kalibrasi" (trigger 2).

**Penting untuk user:** kalibrasi menganggap pose sekarang **rata**. Kalau drone duduk miring karena baterai/kaki tidak rata, kalibrasi ikut
menyimpan kemiringan itu dan drone akan terbang condong. Taruh drone di posisi yang sama seperti saat terbang (baterai terpasang, kaki rata).

### Uji

| Langkah | Selesai kalau |
|---|---|
| Buka app, drone datar | Tilt tampil & berubah saat drone dimiringkan (kanan turun → R positif, depan turun → P negatif) |
| SET LEVEL 0° di permukaan rata | Tilt ≈ 0°/0° dalam 1 s |
| Matikan & nyalakan drone | Tilt tetap ≈ 0° (tersimpan di NVS) |
| Thrust kecil (±4000) di lantai rata | keempat motor kurang lebih sama (bukan cuma 2) |

## 13. Review mode terbang (2026-10-06)

Hasil cek kode app (`ControlState.kt`, `Crtp.kt`, `DroneLink.kt`) terhadap firmware:

| Item | Status | Catatan |
|---|---|---|
| Format setpoint 16 byte, header 0x30, checksum | ✅ benar | cocok dengan `crtp_commander_rpyt.c:48`, `wifi_esp32.c:54` |
| Thrust 0..60000, deadzone, unlock setelah stik bawah | ✅ benar | firmware juga punya thrust lock sendiri |
| Kirim 50 Hz + echo 5 Hz, stop → thrust 0 ×5 | ✅ benar | di bawah watchdog 500 ms |
| Roll: stik kanan → roll + | ⚠️ **perlu uji bench** | state roll + = kanan turun, jadi kemungkinan benar, tapi mode CPPM firmware membalik roll (`crtp_commander_generic.c:217`) |
| Pitch: stik maju → pitch + (`PITCH_SIGN = 1f`) | ❌ **kemungkinan terbalik** | state pitch − = hidung turun, controller mengejar setpoint → +15° = hidung **naik** = mundur. Mode CPPM firmware juga memakai `-1 ×` stik pitch (`crtp_commander_generic.c:218`). Kemungkinan perlu `PITCH_SIGN = -1f` |
| Yaw: stik kanan → yaw + | ✅ kemungkinan benar | firmware membalik input legacy (`crtp_commander_rpyt.c:251`) |

Sebagian besar "anomali" yang terlihat di lantai **bukan dari app**, tapi dari firmware/hardware dan sudah ditangani di firmware:
offset level (bagian 12), integral PID menumpuk saat drone tertahan di lantai (firmware sekarang me-reset integral di thrust < 10000),
baterai anjlok (ESP reboot di thrust tinggi).

### Uji tanda stik (wajib sebelum terbang, tanpa propeller)

Pakai firmware dengan `DEBUG_PRINT_ATTITUDE=y` (log `cmd r/p/y` & PWM), drone di meja, ARM, thrust ±30%, lalu dorong **satu stik** penuh dan lihat PWM:

| Input | Yang benar | Kalau terbalik |
|---|---|---|
| Stik kanan **maju** | M2 & M3 (belakang) **naik**, M1 & M4 turun → hidung turun → maju | ganti `PITCH_SIGN` |
| Stik kanan **ke kanan** | M3 & M4 (kiri) **naik** → miring kanan → geser kanan | tambah `ROLL_SIGN = -1f` |
| Stik kiri **ke kanan** | M1 & M3 (motor CCW) **naik** → badan berputar searah jarum jam (kanan) | tambah `YAW_SIGN = -1f` |
