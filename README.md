# Iman o Taqwa (Android)

Bilingual (**English / اردو** with RTL support) offline-first Islamic daily-habits tracker, Tasbeeh counter, Diary with Word (`.doc`) export, Calendar, and exact background Alarms.

## Package & App Info
- **App Name:** `Iman o Taqwa`
- **Application ID (Package):** `com.smof.imanotaqwa`
- **Min SDK:** 24 (Android 7.0) · **Target SDK:** 36
- **Single-source UI:** `app/src/main/assets/www/index.html` (with offline bundled fonts in `app/src/main/assets/www/fonts/`)
- **Alarm Sounds:** `app/src/main/res/raw/{bell,beep,chime,soft}.wav`

---

## Google Sign-In & Drive `appDataFolder` Setup (Click-by-Click)

1. **Google Cloud Console:**
   - Go to [Google Cloud Console](https://console.cloud.google.com/) and create a new project (e.g., `Iman o Taqwa`).
   - Open **APIs & Services → Library**, search for **Google Drive API**, and click **Enable**.
2. **OAuth Consent Screen:**
   - Open **APIs & Services → OAuth consent screen** (or **Google Auth Platform → Audience / Data Access**).
   - Select **External**, fill in App name (`Iman o Taqwa`), User support email, and Developer contact email.
   - Under **Scopes**, add `https://www.googleapis.com/auth/drive.appdata` (alongside `openid`, `profile`, `email`).
   - Under **Test users**, add your Gmail address (e.g. `smomerfarooq16@gmail.com`) while in Testing mode.
3. **Create OAuth Client IDs:**
   - **Web application Client ID:**
     - **Credentials → Create Credentials → OAuth client ID → Web application**
     - Copy the generated **Web Client ID** (ends in `.apps.googleusercontent.com`).
     - Paste it into `var GCID='YOUR_WEB_CLIENT_ID.apps.googleusercontent.com'` in `app/src/main/assets/www/index.html`.
   - **Android Client ID (Debug):**
     - **Credentials → Create Credentials → OAuth client ID → Android**
     - Package name: `com.smof.imanotaqwa`
     - SHA-1: obtain from your debug keystore using `keytool` (below).
   - **Android Client ID (Release):**
     - Create a second **Android** OAuth client ID with package name `com.smof.imanotaqwa` and the SHA-1 from your upload/release keystore (or Google Play App Signing SHA-1 once published).
   - *Note:* Do **not** hard-code any Client Secret. Client IDs are public.

---

## Keystore & SHA-1 Commands

**Get Debug SHA-1:**
```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

**Create Release Upload Keystore (never commit `.jks`/`.keystore` or passwords to git):**
```bash
keytool -genkeypair -v -keystore my-upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

**Get Release SHA-1:**
```bash
keytool -list -v -keystore my-upload-key.jks -alias upload
```

---

## Building Debug APK & Signed Release APK / AAB

```bash
# Debug APK
gradle :app:assembleDebug

# Signed Release APK & AAB (set keystore env vars first)
export KEYSTORE_PATH=/path/to/my-upload-key.jks
export STORE_PASSWORD=your_store_password
export KEY_PASSWORD=your_key_password
gradle :app:assembleRelease :app:bundleRelease
```

Outputs are created under `app/build/outputs/apk/` and `app/build/outputs/bundle/`.

---

## Font Comparison (`--ulh`)
- **Gulzar (`--ulh: 1.75`, default):** Crisp modern Naskh-Nastaleeq hybrid, lightweight and clean at any font scale with zero vertical clipping. **Best all-around default for mobile readability.**
- **Jameel Noori (`--ulh: 1.6`):** Authentic classic Nastaleeq look (most familiar to Urdu readers), compact vertical rhythm, large bundled file (~9.4 MB).
- **Mehr (`--ulh: 1.9`):** Traditional Lahori Nastaleeq calligraphy with taller ascenders; `1.9` line height prevents overlapping lines.
- **Noto Nastaliq Urdu (`--ulh: 2.0`):** Deep diagonal Nastaleeq stacking; requires `2.0` line height for clean separation.
