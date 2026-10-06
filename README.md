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
