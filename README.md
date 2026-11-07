# ومضة · Wamda

<p dir="rtl"><b>نقل الملفات بين الهواتف بأقصى سرعة، دون إنترنت ودون إعلانات.</b><br>
تطبيق أندرويد يربط هاتفين مباشرة عبر Wi-Fi Direct على نطاق 5GHz، يشفّر كل اتصال بـ TLS 1.3، ويستكمل النقل بعد أي انقطاع. يدعم أيضًا النقل إلى الكمبيوتر من المتصفح، والنقل بكابل USB-C، والإرسال لعدة أجهزة.</p>

**Fast phone-to-phone file transfer, no internet, no ads.** An Android app that links two phones directly over Wi-Fi Direct on 5GHz, encrypts every connection with TLS 1.3 and resumes after interruptions. Also transfers to a computer through its browser, over a USB-C cable, and to several receivers at once.

---

## المزايا · Features

| | العربية | English |
|---|---|---|
| ⚡ | 4–8 اتصالات متوازية على Wi-Fi Direct 5GHz، أجزاء 4 MiB | 4–8 parallel streams over 5GHz Wi-Fi Direct, 4 MiB chunks |
| 🔒 | TLS 1.3 لكل اتصال، شهادة مولَّدة داخل التطبيق وبصمتها في كود QR | TLS 1.3 everywhere; in-app certificate pinned through the QR payload |
| 🔁 | استكمال بعد الانقطاع، وإعادة اتصال تلقائية على الطرفين | Resume after interruption, automatic reconnection on both sides |
| 📂 | إرسال المجلدات بنفس بنيتها، وتخطي الملفات الموجودة لدى المستقبل | Folders with structure; files the receiver already has are skipped |
| 📡 | اكتشاف الأجهزة القريبة عبر Bluetooth LE، أو كود QR | Nearby discovery over Bluetooth LE, or a QR code |
| 👥 | وضع البث: مرسل واحد لعدة مستقبلين بالتوازي | Broadcast: one sender, several receivers in parallel |
| 💻 | وضع الكمبيوتر: صفحة متصفح للتنزيل والرفع دون أي برنامج | PC mode: a browser page for download and upload, nothing to install |
| 🔌 | النقل بكابل USB-C، واستخدام Wi-Fi والكابل معًا | USB-C cable transfer, and Wi-Fi + USB multipath |
| 🌐 | Wi-Fi Aware عند توفره، مع بقاء الإنترنت متصلًا | Wi-Fi Aware when available, internet stays connected |
| 🧾 | سجل محلي، جلسة مفتوحة لإرسال المزيد، مشاركة من المعرض | Local history, open session for more files, share-sheet target |

## كيف يعمل · How it works

```
Receiver                                     Sender
────────                                     ──────
Wi-Fi Direct group (5GHz)  ──QR / BLE──▶     joins the network (WifiNetworkSpecifier)
TLS 1.3 server :48080      ◀──TLS──────      control stream: HELLO, MANIFEST
                           ──NEED──────▶     which chunks are missing (resume / dedupe)
                           ◀══N×DATA════     parallel data streams, 4 MiB frames
MediaStore (Download/Wamda) ──ACK──────▶     DONE
```

- The frame protocol is transport-agnostic: the same code runs over TLS sockets, USB accessory streams, and Wi-Fi Aware data paths. See [`app/src/main/java/app/barq/engine`](app/src/main/java/app/barq/engine).
- The receiver only ever asks for the chunks it lacks, which gives resume, reconnection and duplicate skipping from one mechanism.
- Full product specification (Arabic): [`docs/spec.html`](docs/spec.html).

## البناء · Build

Requirements: JDK 17, Android SDK 35, Gradle wrapper included. `minSdk 29` (Android 10).

```bash
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. On Xiaomi/HyperOS phones enable **Install via USB** in Developer options before `adb install`.

## البنية · Layout

```
app/src/main/java/app/barq/
├── engine/     frame protocol, Sender, Receiver, BroadcastHost, resume store, quick hash, Link
├── crypto/     in-app TLS identity and socket factories
├── net/        Wi-Fi Direct host, Wi-Fi client, BLE advertise/scan, Wi-Fi Aware, usb/ (AOA + mux)
├── web/        PC mode: HTTP server and session
├── data/       settings, history, media repository
└── ui/         Jetpack Compose screens and theme
docs/spec.html  product specification
```

## الحالة · Status

Tested on real devices: phone-to-phone over 5GHz Wi-Fi Direct (741 MB at ~39 MB/s on mid-range phones), resume, folders, duplicate skipping, share sheet, PC mode in both directions.
Implemented but not yet verified on hardware: broadcast mode, USB-C cable and multipath, Wi-Fi Aware (needs two supporting phones).

## الخصوصية · Privacy

Everything stays between the two devices. No accounts, servers, analytics or ads. Details in the in-app Privacy Policy and in `docs/spec.html` §08.

## الترخيص · License

Not decided yet. Fonts: [Readex Pro](https://fonts.google.com/specimen/Readex+Pro) and [IBM Plex Sans Arabic](https://fonts.google.com/specimen/IBM+Plex+Sans+Arabic) under the SIL Open Font License.
