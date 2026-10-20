package com.bainbridgetrails.data

import java.text.Normalizer

actual fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
