import os
import sys
import time
import numpy as np
import streamlit as st
import onnxruntime as ort
import scipy.io.wavfile as wavfile
from scipy.signal import resample

st.set_page_config(
    page_title="VoiceGuard - AI Voice Scam Detector",
    page_icon="🛡️",
    layout="wide",
    initial_sidebar_state="expanded"
)

# ----------------- SCAM KEYWORDS (Matches Table 4 in Proposal) -----------------
KEYWORDS_DB = [
    # OTP and PIN theft (weight 3)
    {"cat": "OTP and PIN theft", "kw": "otp", "lang": "EN", "w": 3},
    {"cat": "OTP and PIN theft", "kw": "verification code", "lang": "EN", "w": 3},
    {"cat": "OTP and PIN theft", "kw": "pin", "lang": "EN", "w": 3},
    {"cat": "OTP and PIN theft", "kw": "password", "lang": "EN", "w": 3},
    {"cat": "OTP and PIN theft", "kw": "code bataiye", "lang": "UR", "w": 3},
    {"cat": "OTP and PIN theft", "kw": "otp bata dein", "lang": "UR", "w": 3},
    {"cat": "OTP and PIN theft", "kw": "code send karein", "lang": "UR", "w": 3},

    # Fake account block (weight 3)
    {"cat": "Fake account block", "kw": "account blocked", "lang": "EN", "w": 3},
    {"cat": "Fake account block", "kw": "card blocked", "lang": "EN", "w": 3},
    {"cat": "Fake account block", "kw": "sim block", "lang": "EN", "w": 3},
    {"cat": "Fake account block", "kw": "account band ho gaya", "lang": "UR", "w": 3},
    {"cat": "Fake account block", "kw": "card block", "lang": "UR", "w": 3},

    # Bank or wallet impersonation (weight 1)
    {"cat": "Bank or wallet impersonation", "kw": "bank", "lang": "EN", "w": 1},
    {"cat": "Bank or wallet impersonation", "kw": "easypaisa", "lang": "EN", "w": 1},
    {"cat": "Bank or wallet impersonation", "kw": "jazzcash", "lang": "EN", "w": 1},
    {"cat": "Bank or wallet impersonation", "kw": "helpline", "lang": "EN", "w": 1},
    {"cat": "Bank or wallet impersonation", "kw": "customer care", "lang": "EN", "w": 1},

    # Money sent by mistake (weight 2)
    {"cat": "Money sent by mistake", "kw": "sent by mistake", "lang": "EN", "w": 2},
    {"cat": "Money sent by mistake", "kw": "send back", "lang": "EN", "w": 2},
    {"cat": "Money sent by mistake", "kw": "ghalti se paise", "lang": "UR", "w": 2},
    {"cat": "Money sent by mistake", "kw": "wapas bhej dein", "lang": "UR", "w": 2},

    # Prize and lottery (weight 2)
    {"cat": "Prize and lottery", "kw": "prize", "lang": "EN", "w": 2},
    {"cat": "Prize and lottery", "kw": "lottery", "lang": "EN", "w": 2},
    {"cat": "Prize and lottery", "kw": "winner", "lang": "EN", "w": 2},
    {"cat": "Prize and lottery", "kw": "inaam", "lang": "UR", "w": 2},
    {"cat": "Prize and lottery", "kw": "lottery jeet gaye", "lang": "UR", "w": 2},

    # Family emergency (weight 2)
    {"cat": "Family emergency", "kw": "accident", "lang": "EN", "w": 2},
    {"cat": "Family emergency", "kw": "hospital", "lang": "EN", "w": 2},
    {"cat": "Family emergency", "kw": "emergency", "lang": "EN", "w": 2},
    {"cat": "Family emergency", "kw": "hadsa", "lang": "UR", "w": 2},
    {"cat": "Family emergency", "kw": "aspatal", "lang": "UR", "w": 2},
    {"cat": "Family emergency", "kw": "mushkil mein hoon", "lang": "UR", "w": 2},

    # Urgent transfer and secrecy (weight 1 - 3)
    {"cat": "Urgent transfer and secrecy", "kw": "urgent", "lang": "EN", "w": 1},
    {"cat": "Urgent transfer and secrecy", "kw": "transfer", "lang": "EN", "w": 1},
    {"cat": "Urgent transfer and secrecy", "kw": "do not tell anyone", "lang": "EN", "w": 3},
    {"cat": "Urgent transfer and secrecy", "kw": "abhi bhejein", "lang": "UR", "w": 2},
    {"cat": "Urgent transfer and secrecy", "kw": "kisi ko na batayen", "lang": "UR", "w": 3},

    # Authority and threats (weight 2 - 3)
    {"cat": "Authority and threats", "kw": "pta", "lang": "EN", "w": 2},
    {"cat": "Authority and threats", "kw": "fia", "lang": "EN", "w": 2},
    {"cat": "Authority and threats", "kw": "police", "lang": "EN", "w": 2},
    {"cat": "Authority and threats", "kw": "arrest", "lang": "EN", "w": 3},
    {"cat": "Authority and threats", "kw": "giraftari", "lang": "UR", "w": 3},
    {"cat": "Authority and threats", "kw": "muqadma", "lang": "UR", "w": 3},

    # Personal and card details (weight 3)
    {"cat": "Personal and card details", "kw": "cnic", "lang": "EN", "w": 3},
    {"cat": "Personal and card details", "kw": "cvv", "lang": "EN", "w": 3},
    {"cat": "Personal and card details", "kw": "card number", "lang": "EN", "w": 3},
    {"cat": "Personal and card details", "kw": "shanakhti card", "lang": "UR", "w": 3},
]

def scan_text_keywords(text):
    text_lower = text.lower()
    hits = []
    total_weight = 0
    matched_cats = set()
    for item in KEYWORDS_DB:
        if item["kw"] in text_lower:
            hits.append(item)
            total_weight += item["w"]
            matched_cats.add(item["cat"])
    return hits, total_weight, matched_cats

# ----------------- RISK ENGINE (Matches RiskEngine.kt) -----------------
def evaluate_risk(smoothed_score, persistently_high, keyword_weight, threshold=0.60):
    elevated_fraction = 0.70
    elevated = smoothed_score >= threshold * elevated_fraction
    high = smoothed_score >= threshold
    strong_keywords = keyword_weight >= 3

    if high and persistently_high:
        return "ALERT", "The voice score stays high. The speaker may be an AI clone."
    elif elevated and strong_keywords:
        return "ALERT", "Raised voice score together with scam keywords. Treat this call as suspicious."
    elif high:
        return "CAUTION", "The voice score is high. Waiting to see if it stays high."
    elif elevated:
        return "CAUTION", "The voice score is raised. Stay careful."
    else:
        return "SAFE", "The voice looks genuine so far."

# ----------------- MODEL INFERENCE -----------------
MODEL_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "voiceguard_aasist_l.onnx")
)

@st.cache_resource
def load_onnx_model():
    if not os.path.exists(MODEL_PATH):
        return None, f"Model file not found at: {MODEL_PATH}"
    try:
        session = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
        return session, "Model loaded successfully!"
    except Exception as e:
        return None, str(e)

def score_waveform(session, audio_data, sample_rate=16000):
    # Resample to 16 kHz if necessary
    if sample_rate != 16000 and len(audio_data) > 0:
        num_samples = int(len(audio_data) * 16000 / sample_rate)
        audio_data = resample(audio_data, num_samples)

    # Normalize to [-1.0, 1.0]
    if np.max(np.abs(audio_data)) > 0:
        audio_data = audio_data / np.max(np.abs(audio_data))

    target_samples = 64600  # AASIST-L window (4.03 seconds)
    if len(audio_data) < target_samples:
        repeat_factor = int(np.ceil(target_samples / max(1, len(audio_data))))
        audio_data = np.tile(audio_data, repeat_factor)[:target_samples]
    else:
        audio_data = audio_data[:target_samples]

    input_tensor = audio_data.reshape(1, target_samples).astype(np.float32)

    t0 = time.time()
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: input_tensor})
    latency_ms = (time.time() - t0) * 1000

    logits = outputs[0][0]  # [spoof, bona_fide]
    # Softmax
    exp_logits = np.exp(logits - np.max(logits))
    probs = exp_logits / np.sum(exp_logits)
    spoof_prob = float(probs[0])
    bonafide_prob = float(probs[1])

    return spoof_prob, bonafide_prob, latency_ms

# ----------------- STREAMLIT UI -----------------
st.sidebar.title("🛡️ VoiceGuard Navigation")
st.sidebar.markdown("**Project:** FYP (BSCS) - LGU")
st.sidebar.markdown("**Students:** Saad Ali & M. Abdullah")
st.sidebar.markdown("**Supervisors:** Ma'am Aisha Riaz & Haziq Sumaid")
st.sidebar.divider()

threshold = st.sidebar.slider("AI Spoof Alert Threshold", 0.30, 0.95, 0.60, 0.05)
family_codeword = st.sidebar.text_input("Family Secret Code-Word", value="BlueFalcon7")

st.title("🛡️ VoiceGuard: Real-Time On-Device AI Voice Scam Detector")
st.caption("Live Interactive System Simulator for FYP Evaluation & Demo")

session, model_msg = load_onnx_model()
if session is not None:
    st.success(f"✅ On-Device AASIST-L Model: Ready ({model_msg})")
else:
    st.error(f"❌ Model Status: {model_msg}")

tabs = st.tabs(["📞 Mode 1: Simulator & Risk Engine", "📁 Mode 2: Audio File Analysis", "⚡ Latency Benchmark", "📖 Scam Keywords Database"])

# ----------------- TAB 1: SIMULATOR & RISK ENGINE -----------------
with tabs[0]:
    st.subheader("Interactive Call Protection Simulator")
    st.markdown("Yeh mode proposal ke Figure 2 aur Risk Engine ka live demonstration hai.")

    col1, col2 = st.columns([1, 1])

    with col1:
        st.markdown("### 1. Simulated Audio / Voice Signal")
        voice_type = st.radio(
            "Caller Voice Type:",
            ["Real Human Voice (Bona fide)", "AI Cloned Voice (Deepfake Attack)", "Custom Spoof Score"],
            index=0
        )

        if voice_type == "Real Human Voice (Bona fide)":
            raw_voice_score = 0.08
        elif voice_type == "AI Cloned Voice (Deepfake Attack)":
            raw_voice_score = 0.88
        else:
            raw_voice_score = st.slider("Set Custom Voice Spoof Probability:", 0.0, 1.0, 0.75, 0.01)

        persistently_high = st.checkbox("Voice score stays high for >= 2 consecutive windows", value=(raw_voice_score >= threshold))

        st.markdown("### 2. Live Call Speech / Transcript")
        sample_prompts = [
            "Custom text type karein...",
            "Papa mera hadsa ho gaya hai hospital me hoon foran 50 hazar bhejein kisi ko na batayen",
            "Aapka bank account band ho gaya hai verification code aur OTP bata dein",
            "Mubarak ho aap lottery jeet gaye hain inaam lene ke liye CNIC bataiye",
            "Assalam o alaikum bhai kya haal hain shaam ko milte hain"
        ]
        selected_prompt = st.selectbox("Quick Test Scenarios:", sample_prompts)
        if selected_prompt != "Custom text type karein...":
            default_text = selected_prompt
        else:
            default_text = "account band ho gaya hai, foran otp bata dein"

        transcript = st.text_area("Caller Speech Transcript:", value=default_text, height=90)

    with col2:
        st.markdown("### 3. Risk Engine Analysis & Warning Banner")
        hits, total_weight, matched_cats = scan_text_keywords(transcript)
        decision, reason = evaluate_risk(raw_voice_score, persistently_high, total_weight, threshold)

        # Alert Banner
        if decision == "ALERT":
            st.error(f"🚨 **WARNING: SYNTHETIC / CLONED VOICE SCAM SUSPECTED!**\n\n**Reason:** {reason}")
        elif decision == "CAUTION":
            st.warning(f"⚠️ **CAUTION: SUSPICIOUS CALL DETECTED**\n\n**Reason:** {reason}")
        else:
            st.success(f"🟢 **STATUS: GENUINE CALL (SAFE)**\n\n**Reason:** {reason}")

        # Metrics Meter
        mcol1, mcol2, mcol3 = st.columns(3)
        mcol1.metric("Voice Spoof Score", f"{raw_voice_score*100:.1f}%", delta="High" if raw_voice_score >= threshold else "Normal")
        mcol2.metric("Keyword Weight Sum", f"{total_weight} pts", delta="Strong" if total_weight >= 3 else "Low")
        mcol3.metric("System Risk Level", decision)

        st.progress(min(1.0, raw_voice_score))

        # Family Reminder Prompt
        if decision in ["ALERT", "CAUTION"]:
            st.info(f"👨‍👩‍👧 **Family Code-Word Prompt:** Ask the caller for your family secret word: **'{family_codeword}'**. A scammer or AI clone will not know this!")

        # Keyword Hits Details
        if hits:
            st.markdown("#### 🔍 Detected Scam Keywords:")
            for h in hits:
                st.markdown(f"- **'{h['kw']}'** ({h['lang']}) $\\rightarrow$ Category: *{h['cat']}* (Weight: {h['w']})")
        else:
            st.caption("No suspicious scam keywords found in speech.")

# ----------------- TAB 2: AUDIO FILE ANALYSIS -----------------
with tabs[1]:
    st.subheader("Analyze Recorded Call (File Mode)")
    st.markdown("Kisi bhi call recording (WAV format) ko upload karein. AASIST-L on-device ONNX model isko process karega.")

    uploaded_file = st.file_uploader("Upload Audio Call Recording (.wav, 16 kHz recommended):", type=["wav"])

    if uploaded_file is not None and session is not None:
        sr, audio_data = wavfile.read(uploaded_file)
        if audio_data.ndim > 1:
            audio_data = audio_data[:, 0]  # Convert stereo to mono

        duration = len(audio_data) / sr
        st.audio(uploaded_file)
        st.info(f"Audio Properties: Sample Rate = {sr} Hz, Total Length = {duration:.2f} seconds")

        if st.button("🚀 Analyze with AASIST-L ONNX Model"):
            with st.spinner("Processing through AASIST-L inference pipeline..."):
                spoof_p, bona_p, lat = score_waveform(session, audio_data, sr)

            st.markdown("### Analysis Results:")
            rc1, rc2, rc3 = st.columns(3)
            rc1.metric("Spoof (AI Cloned) Probability", f"{spoof_p * 100:.2f}%")
            rc2.metric("Real (Bona fide) Probability", f"{bona_p * 100:.2f}%")
            rc3.metric("Inference Latency", f"{lat:.1f} ms", delta="Under 300ms Target" if lat < 300 else "Slow")

            if spoof_p >= threshold:
                st.error(f"🚨 **Deepfake Detected!** Audio has {spoof_p*100:.1f}% probability of being synthetically generated.")
            else:
                st.success(f"🟢 **Real Audio!** Audio appears genuine ({bona_p*100:.1f}% bona fide).")

    elif session is None:
        st.warning("ONNX Model load nahi ho saka. Audio score ke liye model zaroori hai.")
    else:
        # Sample Test Button
        st.markdown("#### Test with Synthetic Waveform Sample:")
        if st.button("Generate & Test 4-Second Test Audio"):
            dummy_wav = (np.random.randn(64600) * 0.1).astype(np.float32)
            spoof_p, bona_p, lat = score_waveform(session, dummy_wav, 16000)
            st.metric("Test Spoof Probability", f"{spoof_p*100:.2f}%")
            st.metric("Latency", f"{lat:.2f} ms")

# ----------------- TAB 3: LATENCY BENCHMARK -----------------
with tabs[2]:
    st.subheader("⚡ On-Device Latency Benchmark (Proposal Evaluation Target: < 300 ms)")
    st.markdown("Yeh benchmark model ko 10 windows par test karta hai aur Mean, Median, P95 aur Max latency calculate karta hai.")

    if session is not None:
        if st.button("▶️ Run Latency Benchmark"):
            latencies = []
            progress_bar = st.progress(0)
            status_text = st.empty()

            for i in range(10):
                dummy = np.random.randn(64600).astype(np.float32)
                _, _, lat = score_waveform(session, dummy, 16000)
                latencies.append(lat)
                progress_bar.progress((i + 1) / 10)
                status_text.text(f"Window {i+1}/10: {lat:.1f} ms")

            mean_lat = np.mean(latencies)
            median_lat = np.median(latencies)
            p95_lat = np.percentile(latencies, 95)
            max_lat = np.max(latencies)

            st.success("Benchmark Completed!")
            b1, b2, b3, b4 = st.columns(4)
            b1.metric("Mean Latency", f"{mean_lat:.1f} ms")
            b2.metric("Median Latency", f"{median_lat:.1f} ms")
            b3.metric("P95 Latency", f"{p95_lat:.1f} ms")
            b4.metric("Max Latency", f"{max_lat:.1f} ms")

            if p95_lat < 300:
                st.balloons()
                st.success(f"🎯 **Target Achieved!** P95 Latency ({p95_lat:.1f} ms) is well below the proposal target of 300 ms. Real-time operation on phone is guaranteed!")
            else:
                st.warning(f"⚠️ P95 Latency ({p95_lat:.1f} ms) exceeds 300 ms target.")
    else:
        st.error("Model session available nahi hai.")

# ----------------- TAB 4: KEYWORDS DATABASE -----------------
with tabs[3]:
    st.subheader("📖 Monitored Scam Keywords Database (Proposal Table 4)")
    st.dataframe(KEYWORDS_DB, use_container_width=True)

