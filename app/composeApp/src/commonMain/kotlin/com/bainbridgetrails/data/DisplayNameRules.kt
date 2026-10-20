package com.bainbridgetrails.data

/** Client-side check of a custom display name (product §5 + TL-9). The server is authoritative (blocklist is server-only). */
sealed interface NameCheck {
    data class Ok(val normalized: String) : NameCheck

    /** < 3 characters (`name_err_short`). */
    data object TooShort : NameCheck

    /** > 24 characters. */
    data object TooLong : NameCheck

    /** Outside `[A-Za-z0-9 À-ÖØ-öø-ÿ'._-]`. */
    data object InvalidCharacters : NameCheck

    /** Fewer than 2 letters. */
    data object TooFewLetters : NameCheck

    /** Contains `@`, `www`, `http` or a `.tld` after a letter. */
    data object LooksLikeLink : NameCheck
}

/** Mirrors `checkDisplayName` in `backend/src/moderation/names.ts` except the blocklist (server: `name_rejected`). */
object DisplayNameRules {
    const val MIN = 3
    const val MAX = 24

    // JS `\s` (Unicode whitespace), which the server collapses.
    private val SPACES = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
    private val ALLOWED = Regex("^[A-Za-z0-9 \\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF'._-]+$")
    private val TLD = Regex("\\p{L}\\.[a-z]{2,}\\b")

    /** NFC, trim, collapse whitespace (what the server stores). */
    fun normalize(input: String): String = nfc(input).replace(SPACES, " ").trim()

    /** Length the user sees in `name_counter` (code points of the normalized name). */
    fun length(input: String): Int = normalize(input).let { s -> s.length - s.count { it.isLowSurrogate() } }

    fun check(input: String): NameCheck {
        val name = normalize(input)
        val len = name.length - name.count { it.isLowSurrogate() }
        if (len < MIN) return NameCheck.TooShort
        if (len > MAX) return NameCheck.TooLong
        if (!ALLOWED.matches(name)) return NameCheck.InvalidCharacters
        // Every allowed non-ASCII char is a Latin-1 letter, so isLetter() matches the server's \p{L} here.
        if (name.count { it.isLetter() } < 2) return NameCheck.TooFewLetters
        val lower = name.lowercase()
        if ("@" in lower || "www" in lower || "http" in lower || TLD.containsMatchIn(lower)) return NameCheck.LooksLikeLink
        return NameCheck.Ok(name)
    }
}

/** Unicode NFC (Android: java.text.Normalizer; iOS: precomposedStringWithCanonicalMapping). */
expect fun nfc(s: String): String
