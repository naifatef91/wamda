# نصوص Google Play Console · ومضة (Wamda)

كل ما يُلصق في Play Console عند أول رفع. النصوص بالعربية أولًا ثم الإنجليزية حيث يطلب Play نسختين.

رابط سياسة الخصوصية العام (بعد تفعيل GitHub Pages):
`https://naifatef91.github.io/wamda/privacy.html`

---

## 1. بيانات التطبيق (Store listing)

**الاسم:** ومضة · Wamda

**الوصف القصير (80 حرفًا):**
- ar: نقل الملفات بين الهواتف وإلى الكمبيوتر بسرعة عالية، دون إنترنت وبلا إعلانات.
- en: Fast phone-to-phone and phone-to-PC file transfer. No internet, no ads.

**الوصف الكامل:**

ar:
```
ومضة ينقل الملفات بين هاتفين عبر Wi-Fi مباشر على نطاق 5 جيجاهرتز بسرعة تتجاوز 35 ميجابايت في الثانية، دون إنترنت ودون حساب ودون إعلانات.

• اقتران بمسح كود QR أو من رادار الأجهزة القريبة
• صور وفيديو وصوت وتطبيقات ومجلدات كاملة بنفس البنية
• تخطي الملفات الموجودة لدى المستقبل تلقائيًا
• استكمال النقل بعد الانقطاع من حيث توقف
• وضع الكمبيوتر: افتح رابطًا في متصفح الحاسوب لتنزيل الملفات أو رفعها، بلا برامج
• نقل بكابل USB-C بين هاتفين، أو عبر Wi-Fi والكابل معًا
• بث لعدة مستقبلين دفعة واحدة
• كل اتصال Wi-Fi مشفّر بـ TLS 1.3، والملفات لا تغادر الجهازين أبدًا

التطبيق مفتوح المصدر: github.com/naifatef91/wamda
```

en:
```
Wamda moves files between two phones over direct 5 GHz Wi-Fi at more than 35 MB/s, with no internet, no account and no ads.

• Pair by scanning a QR code or from the nearby-devices radar
• Photos, videos, audio, apps and whole folders with their structure
• Files the receiver already has are skipped automatically
• Interrupted transfers resume where they stopped
• PC mode: open a link in the computer's browser to download or upload files, no software needed
• USB-C cable transfer between phones, or Wi-Fi and cable together
• Broadcast to several receivers at once
• Every Wi-Fi connection is TLS 1.3 encrypted and files never leave the two devices

Open source: github.com/naifatef91/wamda
```

**الفئة:** Tools · **التصنيف العمري:** استبيان IARC، لا محتوى للمستخدمين ولا تفاعل اجتماعي ← Everyone.

---

## 2. بيانات الأمان (Data safety)

- **هل يجمع التطبيق أو يشارك أيًا من أنواع البيانات المطلوبة؟** لا.
- **هل البيانات مشفّرة أثناء النقل؟** نعم (TLS 1.3 على Wi-Fi). ملاحظة للمراجع: وضع الكمبيوتر والكابل روابط محلية مباشرة بلا إنترنت.
- **هل يمكن للمستخدم طلب حذف بياناته؟** لا توجد بيانات لدينا؛ كل شيء على الجهاز ويُحذف بإلغاء التثبيت.
- **الملفات التي يختارها المستخدم** تُرسل من جهازه إلى الجهاز الذي اختاره مباشرة ولا تمر بأي خادم، لذا لا تُعدّ «مجموعة» ولا «مشاركة» وفق تعريف Play (لا تغادر إلى المطوّر ولا إلى طرف ثالث).
- **اسم الجهاز** يُعرض للجهاز الآخر فقط على الرابط المحلي. لا يُرسل إلينا.

---

## 3. تبرير الصلاحيات (Sensitive permissions declaration)

**ACCESS_FINE_LOCATION (أندرويد 10 إلى 12 فقط، maxSdkVersion 32):**
```
Android 10–12 requires fine location to use Wi-Fi Direct and to scan for Bluetooth LE advertisements. Wamda uses it only to discover nearby phones running the app and to create the direct Wi-Fi link. The app never reads, stores or transmits location data, and on Android 13+ it uses NEARBY_WIFI_DEVICES with neverForLocation and BLUETOOTH_SCAN with neverForLocation instead, so the permission is not requested there at all.
```

**BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT:**
```
Bluetooth LE is used only to advertise and discover phones running Wamda nearby (the "radar") and to hand over the pairing details for the Wi-Fi Direct link. No files are carried over Bluetooth and no device addresses are stored.
```

**CAMERA:**
```
Used only to scan the pairing QR code shown by the other phone. No photos are captured, stored or transmitted.
```

**READ_MEDIA_IMAGES / VIDEO / AUDIO / VISUAL_USER_SELECTED:**
```
Lists the user's photos, videos and audio inside the app so they can pick what to send. Partial access ("select photos") is fully supported on Android 14+.
```

**FOREGROUND_SERVICE_DATA_SYNC** (انظر القسم 4).

---

## 4. تبرير خدمة المقدمة (Foreground service · dataSync)

**نوع الخدمة:** dataSync

**الوصف (يُلصق في النموذج):**
```
Wamda transfers files directly between two devices over a local Wi-Fi Direct or USB link. A transfer of several gigabytes takes minutes, and the user naturally turns the screen off or switches apps while it runs. The foreground service keeps the transfer process and a partial wake lock alive for the duration of the transfer only, and shows an ongoing notification with live progress (speed, bytes done, cancel). It starts when a transfer begins and stops as soon as the transfer finishes, fails or is cancelled. Without it, Android would kill the process or suspend the sockets and the transfer would be corrupted or lost. No data is synced with any server; "dataSync" is the closest declared type for a user-initiated bulk data transfer between devices.
```

**الفيديو المطلوب (30 ثانية، يُسجَّل من الهاتف):**
1. افتح ومضة ← استلام، وامسح الكود من هاتف ثانٍ (أو العكس).
2. ابدأ نقل ملف كبير (فيديو 1 GB مثلًا).
3. اسحب شريط الإشعارات لإظهار إشعار التقدّم، ثم اضغط زر الطاقة لإطفاء الشاشة لثوانٍ، ثم أعد تشغيلها.
4. افتح التطبيق وأظهر أن النقل استمر واكتمل، وأن الإشعار اختفى.

ارفع الفيديو على YouTube (غير مدرج) أو Google Drive برابط عام والصقه في الحقل.

---

## 5. الصلاحيات التي لم تعد في البيان

- `QUERY_ALL_PACKAGES` استُبدلت بعنصر `<queries>` لنية `MAIN/LAUNCHER`؛ تبويب التطبيقات يعرض التطبيقات القابلة للتشغيل فقط.

---

## 6. الإصدار الأول

- `versionCode 2`، `versionName 1.1.0` (الإصدار 1.1 حسب وثيقة المواصفات).
- مسار الرفع: Internal testing أولًا، ثم Closed testing مع 12 مختبرًا لمدة 14 يومًا (شرط الحسابات الشخصية الجديدة)، ثم Production.
- الملف المرفوع: `app/build/outputs/bundle/release/app-release.aab` موقّع بمفتاح الرفع في `keystore/upload.jks` (غير مُضمَّن في Git، احتفظ بنسخة احتياطية).
