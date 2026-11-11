# VoiceGuard: Real-Time On-Device Detection of AI-Cloned Voice Scam Calls

[![Android](https://img.shields.io/badge/Platform-Android_8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![ONNX Runtime](https://img.shields.io/badge/Inference-ONNX_Runtime_Mobile-blue.svg)](https://onnxruntime.ai)
[![Vosk](https://img.shields.io/badge/Speech_Recognition-Vosk_Offline-orange.svg)](https://alphacephei.com/vosk)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

---

## 📌 Project Overview
**VoiceGuard** is an offline, privacy-first Android application developed as a **Final Year Project (FYP)** in the **Department of Computer Science at Lahore Garrison University (LGU)**. 

The application protects smartphone users against **AI-cloned voice fraud and impersonation scams** during phone calls. Unlike traditional caller-ID or spam-blocking apps (which only verify phone numbers), VoiceGuard directly analyzes the **acoustic properties of the voice** and detects **scam conversation keywords** simultaneously in real-time.

All audio processing, machine learning inference, and keyword spotting run **100% locally on the device**—no audio is ever recorded to disk or transmitted to any remote server.

---

## 👥 Project Team & Supervision
- **Students:**
  - **Saad Ali** (Roll No: FA23-BSCS-186)
  - **Muhammad Abdullah** (Roll No: FA23-BSCS-493)
- **Supervisors:**
  - **Supervisor:** Ma’am Aisha Riaz
  - **Co-Supervisor:** Haziq Sumaid
- **Department:** Department of Computer Science, Lahore Garrison University (LGU), Lahore.

---

## 🚀 Key Features

1. **Dual-Signal Risk Engine:**
   - **Voice Signal:** Evaluates voice genuineness using the AASIST-L deepfake classifier.
   - **Scam Keywords:** Scans for high-risk phrases across **9 scam categories** (OTP theft, fake account blocking, bank impersonation, fake accidents/family emergency, prize lottery, etc.) in **English, Roman Urdu, and Devanagari script**.
   - **Safety Logic:** Scam keywords alone never trigger a false alarm; alerts require elevated voice spoof scores combined with keyword triggers.

2. **Three Operating Modes:**
   - **Speakerphone (Live Mode):** Foreground service listens to live call audio via microphone when speakerphone is activated.
   - **Recorded Call (File Mode):** Decodes and analyzes saved call recordings (`.wav`, `.mp3`, `.m4a`) across sliding windows.
   - **Test Call (Simulator Mode):** Built-in transcript tester and call practice mode without requiring live phone calls.

3. **On-Device AASIST-L Anti-Spoofing:**
   - Runs directly on mobile CPU via **ONNX Runtime Mobile**.
   - Input: 16 kHz mono waveform (64,600 samples $\approx$ 4.03 seconds).
   - Moving average smoothing over 5 sliding windows with trend indicator (Rising, Stable, Falling).
   - Latency target: $< 300\text{ ms}$ per window for real-time operation.

4. **Multi-Channel Warning System:**
   - Floating Warning Banner (Overlay on top of third-party dialers like WhatsApp or Phone app).
   - Heads-up system notification and vibration patterns.
   - **Family Code-Word Reminder:** Prompts the user with a pre-configured secret family passphrase to verify distressed callers.

5. **Local Data Persistence (Room SQLite):**
   - Stores call sessions, window scores, latency metrics, and alert history locally.
   - Allows users to flag false alarms and export history to CSV.

---

## 🏗️ System Architecture

```text
[Call Audio (Mic / File)]
           │
           ▼
[16 kHz Mono Audio Stream]
           │
           ├────────────────────────────────────────┬────────────────────────────────────────┐
           ▼ (50% Overlap Sliding Windows)          │                                        ▼ (Offline Speech Stream)
 [Window Buffer: 2 to 4 Seconds]                    │                               [Vosk Speech-to-Text]
           │                                        │                                        │
           ▼                                        │                                        ▼
[AASIST-L ONNX Model Inference]                     │                               [Scam Keyword Matcher]
           │                                        │                               (Weights: 1 to 3)
           ▼                                        │                                        │
[Score Smoother (5-Window Moving Avg)]              │                                        │
           │                                        │                                        │
           └───────────────────────────────┬────────┴────────────────────────────────────────┘
                                           ▼
                                   [RiskEngine Decision]
                                           │
                           ┌───────────────┴───────────────┐
                           ▼                               ▼
                   [Alert Triggered]                 [Safe / Caution]
                           │                               │
                           ▼                               ▼
                 • Floating Overlay Banner          UI Metric Updates
                 • Heads-Up Notification            (Confidence & Trend)
                 • Phone Vibration Pattern
                 • Room DB Session Log
```

---

## 📂 Repository Structure

```text
VoiceGuard/
├── app/                                    # Android Application module
│   ├── src/main/
│   │   ├── AndroidManifest.xml             # Foreground service & mic permissions
│   │   ├── assets/                         # Machine learning model assets
│   │   │   ├── AASIST_LICENSE.txt          # MIT license
│   │   │   └── voiceguard_aasist_l.onnx    # AASIST-L ONNX model file
│   │   ├── java/com/voiceguard/app/
│   │   │   ├── MainActivity.kt             # Main dashboard & mode selector
│   │   │   ├── VoiceGuardApp.kt            # Application lifecycle & DB init
│   │   │   ├── audio/                      # Audio recording, decoding & foreground service
│   │   │   │   ├── AlertNotifier.kt        # Notifications and vibration alerts
│   │   │   │   ├── AudioFileDecoder.kt     # Hardware audio decoding & resampling
│   │   │   │   ├── AudioRecorderManager.kt # 16 kHz mono microphone capture
│   │   │   │   ├── FileAnalyzer.kt         # Audio file window analysis
│   │   │   │   ├── OverlayBanner.kt        # Floating call alert overlay
│   │   │   │   └── VoiceGuardService.kt    # Foreground service for call monitoring
│   │   │   ├── data/                       # Room Database & Settings
│   │   │   │   ├── AppDatabase.kt          # SQLite database configuration
│   │   │   │   ├── Daos.kt                 # Room DAOs for sessions & scores
│   │   │   │   ├── DefaultKeywords.kt      # Table 4 Scam keyword definitions
│   │   │   │   ├── Entities.kt             # CallSession, WindowScore, ScamKeyword entities
│   │   │   │   └── SettingsStore.kt        # User preferences (threshold, window size)
│   │   │   ├── ml/                         # ML inference & detection logic
│   │   │   │   ├── AnalysisPipeline.kt     # Core windowing & scoring pipeline
│   │   │   │   ├── RiskEngine.kt           # Dual-signal risk evaluation
│   │   │   │   ├── ScamKeywordDetector.kt  # Offline regex & keyword matching
│   │   │   │   ├── ScoreSmoother.kt        # Moving average & trend tracker
│   │   │   │   ├── VoiceGuardClassifier.kt # ONNX Runtime Mobile inference wrapper
│   │   │   │   └── VoskModels.kt           # Vosk offline STT wrapper
│   │   │   ├── model/                      # UI state and event models
│   │   │   └── ui/                         # Activities (History, Settings, Keywords)
│   │   └── res/                            # Layouts, strings, drawables & styles
│   └── build.gradle.kts                    # App dependencies (Room, ONNX, Vosk)
├── gradle/wrapper/                         # Gradle wrapper configuration
├── tools/                                  # Research, Training & Simulation tools
│   ├── export_aasist_l_onnx.py             # PyTorch AASIST-L to ONNX exporter
│   └── web_simulator.py                    # Streamlit web dashboard for live demo
├── .gitignore                              # Git ignore rules for Android and Python
├── build.gradle.kts                        # Root project build script
├── settings.gradle.kts                     # Gradle settings
└── README.md                               # Project documentation
```

---

## 🛠️ Technology Stack

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Mobile OS** | Android (API 26+) | Target SDK 34 (Android 14) |
| **Programming Language** | Kotlin & Python | Kotlin for Android app, Python for ML/Tools |
| **Deepfake Model** | AASIST-L | Graph attention anti-spoofing model (~85k params) |
| **On-Device Inference** | ONNX Runtime Mobile | High-performance mobile execution engine |
| **Speech-to-Text** | Vosk Android SDK | Offline phonetic speech recognition |
| **Local Storage** | Room (SQLite) | Local session, score, and alert storage |
| **UI Framework** | Android Jetpack / XML | ViewBinding, Material Components 3 |
| **Simulation** | Streamlit & SciPy | Interactive browser demonstration tool |

---

## 💻 How to Run

### Option 1: Run the Android App (Android Studio)
1. Open **Android Studio** (Hedgehog 2023.1.1 or newer).
2. Click **File $\rightarrow$ Open** and select the `VoiceGuard` root folder.
3. Allow Gradle to synchronize dependencies.
4. Connect an Android smartphone (Android 8.0+) via USB with **USB Debugging** enabled (or start an Android Emulator).
5. Press the green **Run ▶️** button in Android Studio.
6. Grant **Microphone**, **Notification**, and **Display over other apps** (Overlay) permissions when prompted.

### Option 2: Run the Web Simulator & Demonstration
To quickly test the risk engine and ONNX model on a PC without an Android device:
```bash
pip install streamlit onnxruntime numpy scipy
streamlit run tools/web_simulator.py
```
Open `http://localhost:8501` in your browser.

---

## 📊 Monitored Scam Categories (Table 4)

VoiceGuard monitors 9 scam categories in English and Urdu:
1. **OTP and PIN Theft:** `otp`, `verification code`, `code bataiye`, `pin`
2. **Fake Account Block:** `account blocked`, `card blocked`, `account band ho gaya`, `sim block`
3. **Bank Impersonation:** `bank`, `easypaisa`, `jazzcash`, `helpline`
4. **Money Sent by Mistake:** `sent by mistake`, `ghalti se paise`, `wapas bhej dein`
5. **Prize & Lottery Scams:** `prize`, `lottery`, `inaam`, `lottery jeet gaye`
6. **Family Emergency / Accident:** `accident`, `hospital`, `hadsa`, `aspatal`, `mushkil mein hoon`
7. **Urgent Transfer & Secrecy:** `urgent`, `transfer`, `abhi bhejein`, `kisi ko na batayen`
8. **Authority Threats:** `police`, `fia`, `pta`, `arrest`, `giraftari`, `muqadma`
9. **Personal & Card Details:** `cnic`, `cvv`, `card number`, `shanakhti card`

---

## 📜 License & Citation
- **VoiceGuard Application & Research:** Developed by Saad Ali & Muhammad Abdullah (Lahore Garrison University).
- **AASIST Model:** Licensed under the MIT License by NAVER Corp. / Clova AI.
- **Vosk SDK:** Licensed under the Apache 2.0 License by Alpha Cephei Inc.
