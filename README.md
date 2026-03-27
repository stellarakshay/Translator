# Travel Translator (Ultra-light Android APK)

A lightweight Android translator for travel conversations (English <-> Greek/Italian/French), with automatic conversation auditing and summary.

## What it does
1. Choose one foreign language: **Greek**, **Italian**, or **French**.
2. Tap **Start Conversation** once.
3. App continuously listens and auto-translates both directions:
   - English -> chosen language
   - chosen language -> English
4. Optional fallback: type a sentence and tap **Send** if speech recognition is noisy/unreliable.
5. Tap **Stop Conversation** to finish.
6. You get a full **conversation audit** plus an **AI-style summary** in both languages.

Audits are auto-cleaned after ~2 days to keep storage use low.

## APKs generated in this repo
After building, use one of these:
- **Debug APK (installable immediately):**
  - `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK (unsigned):**
  - `app/build/outputs/apk/release/app-release-unsigned.apk`

For easy local testing on your phone, use the **debug APK**.

## Build commands
```bash
# One-time (this environment)
# local.properties should point to Android SDK path

./gradlew clean :app:assembleDebug :app:assembleRelease
```

## Simple phone install instructions (no Android Studio)
1. Copy `app-debug.apk` to your phone (USB, Drive, Telegram, etc.).
2. On phone: open the APK file.
3. If prompted, allow installs from this source.
4. Install and open **Travel Translator**.
5. Grant microphone permission.

## Run on phone
1. Select your target language.
2. Tap **Start Conversation** and talk naturally back-and-forth.
3. Tap **Stop Conversation** to view:
   - full bilingual audit log
   - AI-style summary (English + translated)

## Notes
- Internet is required for translation and summary translation.
- If voice data for Greek/Italian/French is missing, Android may prompt voice package installation.
