package com.voiceguard.app.data

/**
 * Initial scam keyword list. Matches Table 4 of the VoiceGuard proposal
 * (English and Urdu in Roman script). Devanagari spellings are added so that the
 * offline Hindi/Urdu Vosk model (which prints Devanagari) can match the same words.
 *
 * A keyword is only a hint and never proves fraud. Weights: 1 = weak, 2 = medium, 3 = strong.
 */
object DefaultKeywords {

    const val CAT_OTP = "OTP and PIN theft"
    const val CAT_BLOCK = "Fake account block"
    const val CAT_BANK = "Bank or wallet impersonation"
    const val CAT_MISTAKE = "Money sent by mistake"
    const val CAT_PRIZE = "Prize and lottery"
    const val CAT_FAMILY = "Family emergency"
    const val CAT_URGENT = "Urgent transfer and secrecy"
    const val CAT_AUTHORITY = "Authority and threats"
    const val CAT_PERSONAL = "Personal and card details"

    val categories = listOf(
        CAT_OTP, CAT_BLOCK, CAT_BANK, CAT_MISTAKE, CAT_PRIZE,
        CAT_FAMILY, CAT_URGENT, CAT_AUTHORITY, CAT_PERSONAL
    )

    private fun k(category: String, keyword: String, language: String, weight: Int) =
        ScamKeyword(category = category, keyword = keyword, language = language, weight = weight)

    fun build(): List<ScamKeyword> = listOf(
        // OTP and PIN theft
        k(CAT_OTP, "otp", "EN", 3),
        k(CAT_OTP, "verification code", "EN", 3),
        k(CAT_OTP, "pin", "EN", 3),
        k(CAT_OTP, "password", "EN", 3),
        k(CAT_OTP, "code bataiye", "UR", 3),
        k(CAT_OTP, "otp bata dein", "UR", 3),
        k(CAT_OTP, "ओटीपी", "HI", 3),
        k(CAT_OTP, "पिन", "HI", 3),
        k(CAT_OTP, "पासवर्ड", "HI", 3),
        k(CAT_OTP, "कोड बताइए", "HI", 3),

        // Fake account block
        k(CAT_BLOCK, "account blocked", "EN", 3),
        k(CAT_BLOCK, "card blocked", "EN", 3),
        k(CAT_BLOCK, "sim block", "EN", 3),
        k(CAT_BLOCK, "account band ho gaya", "UR", 3),
        k(CAT_BLOCK, "card block", "UR", 3),
        k(CAT_BLOCK, "अकाउंट बंद हो गया", "HI", 3),
        k(CAT_BLOCK, "कार्ड ब्लॉक", "HI", 3),
        k(CAT_BLOCK, "सिम ब्लॉक", "HI", 3),

        // Bank or wallet impersonation (weak: risky only together with another signal)
        k(CAT_BANK, "bank", "EN", 1),
        k(CAT_BANK, "easypaisa", "EN", 1),
        k(CAT_BANK, "jazzcash", "EN", 1),
        k(CAT_BANK, "helpline", "EN", 1),
        k(CAT_BANK, "customer care", "EN", 1),
        k(CAT_BANK, "बैंक", "HI", 1),
        k(CAT_BANK, "ईज़ीपैसा", "HI", 1),
        k(CAT_BANK, "जैज़कैश", "HI", 1),
        k(CAT_BANK, "हेल्पलाइन", "HI", 1),

        // Money sent by mistake
        k(CAT_MISTAKE, "sent by mistake", "EN", 2),
        k(CAT_MISTAKE, "send back", "EN", 2),
        k(CAT_MISTAKE, "ghalti se paise", "UR", 2),
        k(CAT_MISTAKE, "wapas bhej dein", "UR", 2),
        k(CAT_MISTAKE, "गलती से पैसे", "HI", 2),
        k(CAT_MISTAKE, "वापस भेज दें", "HI", 2),

        // Prize and lottery
        k(CAT_PRIZE, "prize", "EN", 2),
        k(CAT_PRIZE, "lottery", "EN", 2),
        k(CAT_PRIZE, "winner", "EN", 2),
        k(CAT_PRIZE, "inaam", "UR", 2),
        k(CAT_PRIZE, "lottery jeet gaye", "UR", 2),
        k(CAT_PRIZE, "इनाम", "HI", 2),
        k(CAT_PRIZE, "लॉटरी", "HI", 2),

        // Family emergency (typical cloned-voice story)
        k(CAT_FAMILY, "accident", "EN", 2),
        k(CAT_FAMILY, "hospital", "EN", 2),
        k(CAT_FAMILY, "emergency", "EN", 2),
        k(CAT_FAMILY, "police", "EN", 1),
        k(CAT_FAMILY, "hadsa", "UR", 2),
        k(CAT_FAMILY, "aspatal", "UR", 2),
        k(CAT_FAMILY, "mushkil mein hoon", "UR", 2),
        k(CAT_FAMILY, "हादसा", "HI", 2),
        k(CAT_FAMILY, "अस्पताल", "HI", 2),
        k(CAT_FAMILY, "मुश्किल में हूं", "HI", 2),

        // Urgent transfer and secrecy
        k(CAT_URGENT, "urgent", "EN", 1),
        k(CAT_URGENT, "transfer", "EN", 1),
        k(CAT_URGENT, "do not tell anyone", "EN", 3),
        k(CAT_URGENT, "abhi bhejein", "UR", 2),
        k(CAT_URGENT, "kisi ko na batayen", "UR", 3),
        k(CAT_URGENT, "अभी भेजें", "HI", 2),
        k(CAT_URGENT, "किसी को ना बताएं", "HI", 3),

        // Authority and threats
        k(CAT_AUTHORITY, "pta", "EN", 2),
        k(CAT_AUTHORITY, "fia", "EN", 2),
        k(CAT_AUTHORITY, "army", "EN", 1),
        k(CAT_AUTHORITY, "case", "EN", 1),
        k(CAT_AUTHORITY, "arrest", "EN", 2),
        k(CAT_AUTHORITY, "muqadma", "UR", 2),
        k(CAT_AUTHORITY, "giraftari", "UR", 2),
        k(CAT_AUTHORITY, "मुकदमा", "HI", 2),
        k(CAT_AUTHORITY, "गिरफ्तारी", "HI", 2),

        // Personal and card details
        k(CAT_PERSONAL, "cnic", "EN", 3),
        k(CAT_PERSONAL, "card number", "EN", 3),
        k(CAT_PERSONAL, "cvv", "EN", 3),
        k(CAT_PERSONAL, "expiry date", "EN", 3),
        k(CAT_PERSONAL, "shanakhti card number", "UR", 3),
        k(CAT_PERSONAL, "शनाख्ती कार्ड", "HI", 3),
        k(CAT_PERSONAL, "सीएनआईसी", "HI", 3)
    )
}
