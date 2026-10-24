package org.ward.android.xmpp

import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.util.ParserUtils
import org.jivesoftware.smack.xml.XmlPullParser

/**
 * Smack 4.4.8 throws a NullPointerException while parsing a stanza error that is missing the
 * required <error type='…'> attribute (StanzaError.Type.fromString(null)). That NPE is not one of
 * the exceptions parseAndProcessStanza handles, so it tears down the whole XMPP connection. Keep
 * the connection alive by consuming the malformed stanza and giving the rest of the stream back
 * to Smack.
 */
class WardXmppConnection(
    configuration: XMPPTCPConnectionConfiguration,
    private val onMalformedStanza: (String) -> Unit = {},
) : XMPPTCPConnection(configuration) {
    override fun parseAndProcessStanza(parser: XmlPullParser) {
        val depth = parser.depth
        try {
            super.parseAndProcessStanza(parser)
        } catch (e: NullPointerException) {
            val raw = runCatching { PacketParserUtils.parseContentDepth(parser, depth).toString() }.getOrNull()
            onMalformedStanza("Ignored malformed incoming stanza: ${e.message ?: "NullPointerException"}" +
                if (raw.isNullOrBlank()) "" else "\n$raw")
            runCatching { ParserUtils.forwardToEndTagOfDepth(parser, depth) }
        }
    }
}
