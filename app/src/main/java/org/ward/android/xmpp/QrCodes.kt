package org.ward.android.xmpp

import org.ward.android.util.JidUtil

/** Decode an XMPP contact QR value; accepts optional xmpp:, query and resource parts. */
fun contactJidFromQr(text: String): String? {
    val raw = text.trim()
    if (raw.isBlank()) return null
    val withoutScheme = if (raw.startsWith("xmpp:", ignoreCase = true)) raw.substring(5) else raw
    val jid = JidUtil.bare(withoutScheme)
    return jid.takeIf { JidUtil.valid(it) }
}
