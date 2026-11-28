# VideoDownloader

**Download your videos. Choose your quality. Keep them on your device.**

VideoDownloader is an Android application that makes downloading videos simple and convenient. Paste a video link, choose your preferred quality, and let the app handle the rest.

## Features

- **Simple home screen:** Easy access to the app's download features.
- **Animated splash screen:** A smooth introduction when opening the app.
- **Four quality levels:** Low, Medium, High, and Ultra.
- **Video analysis:** View available video formats and estimated file sizes before downloading.
- **Background downloads:** Continue downloading while using other apps.
- **Download notifications:** Monitor download progress and status.
- **Download queue:** Organize multiple downloads and follow their progress.
- **Automatic file saving:** Save completed videos to `Movies/Video Downloader`.
- **Download time estimation:** Estimate download and processing time using collected performance data.

## Supported Platforms

The application uses **yt-dlp** to extract video information and download supported media.

Supported sources include:

- YouTube
- Facebook
- Instagram
- TikTok

Availability depends on the source website, content accessibility, and the installed yt-dlp extractors. Not every link is guaranteed to work.

## How to Use

1. Open VideoDownloader.
2. Choose the download option.
3. Paste the video URL.
4. Analyze the link.
5. Select your preferred quality.
6. Start the download.
7. Find your downloaded video in `Movies/Video Downloader`.

## Download

Get the available APK from the project's [GitHub Releases](../../releases) page.

## Technology Stack

- Kotlin
- Jetpack Compose
- yt-dlp
- FFmpeg
- Kotlin Coroutines
- Android Foreground Services
- Room Database

## Build from Source

### Requirements

- Android Studio
- Android SDK compatible with the project configuration
- Internet connection for dependency resolution

### Build a Debug APK

**Windows:**

```bash
gradlew.bat :app:assembleDebug
```

**Linux / macOS:**

```bash
./gradlew :app:assembleDebug
```

The generated APK is normally located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Responsible Use

Please download only content that you own or have permission to download. Users are responsible for respecting copyright laws and the terms of service of the websites they use.

---

# VideoDownloader — حمّل فيديوهاتك بسهولة

تطبيق أندرويد يساعدك على تنزيل الفيديوهات من المواقع المدعومة وحفظها مباشرة على هاتفك.

الفكرة بسيطة: حط رابط الفيديو، اختار الجودة اللي تناسبك، وسيب التطبيق يتولى عملية التنزيل.

## مميزات التطبيق

- واجهة بسيطة وسهلة الاستخدام.
- شاشة بداية متحركة.
- أربع مستويات للجودة: ضعيفة، متوسطة، قوية، وفائقة.
- عرض الجودات المتاحة والحجم التقديري قبل التنزيل.
- إمكانية استمرار التنزيل في الخلفية.
- متابعة تقدم التنزيل من إشعارات الهاتف.
- تنظيم التنزيلات ومتابعة حالتها.
- حفظ الفيديوهات في مجلد `Movies/Video Downloader`.
- تقدير وقت التنزيل والمعالجة بناءً على بيانات الأداء.

## طريقة الاستخدام

1. افتح التطبيق.
2. اختار طريقة التنزيل.
3. الصق رابط الفيديو.
4. حلّل الرابط.
5. اختار الجودة المناسبة.
6. ابدأ التنزيل.
7. هتلاقي الفيديو في مجلد `Movies/Video Downloader`.

## التحميل

تقدر تحصل على ملفات التطبيق من صفحة [GitHub Releases](../../releases).

## الاستخدام المسؤول

استخدم التطبيق لتنزيل المحتوى الذي تملكه أو لديك إذن بتنزيله، واحترم حقوق النشر وشروط استخدام المواقع.

---

**VideoDownloader — Your videos, saved on your device.**
