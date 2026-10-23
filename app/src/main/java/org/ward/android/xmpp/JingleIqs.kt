package org.ward.android.xmpp

import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.IQProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jingle calls need a raw IQ provider and an IQ request handler.
 *
 * Smack only delivers IQ requests (type=set/get) to registered IQRequestHandlers. A stanza
 * listener never sees them; without a handler Smack answers feature-not-implemented. Smack's
 * built-in JingleProvider also cannot parse the <group semantics="BUNDLE"> element used by GTK,
 * Conversations and Dino, and closes the connection on a NullPointerException.
 */
object JingleIqs {
    private val providerRegistered = AtomicBoolean(false)

    fun register(conn: XMPPConnection, call: CallManager) {
        registerProvider()
        conn.registerIQRequestHandler(object :
            AbstractIqRequestHandler("jingle", JingleSdp.NS_JINGLE, IQ.Type.set, IQRequestHandler.Mode.sync) {
            override fun handleIQRequest(iq: IQ): IQ {
                if (iq is RawJingleIq) {
                    call.onJingleIq(iq, iq.xml)
                    return IQ.createResultIQ(iq)
                }
                return IQ.createErrorResponse(iq, StanzaError.Condition.feature_not_implemented)
            }
        })
    }

    fun registerProvider() {
        if (providerRegistered.compareAndSet(false, true)) {
            ProviderManager.removeIQProvider("jingle", JingleSdp.NS_JINGLE)
            ProviderManager.addIQProvider("jingle", JingleSdp.NS_JINGLE, RawJingleProvider())
        }
    }

    private class RawJingleProvider : IQProvider<RawJingleIq>() {
        override fun parse(parser: XmlPullParser, initialDepth: Int, xmlEnvironment: XmlEnvironment): RawJingleIq {
            val raw = PacketParserUtils.parseElement(parser).toString()
            return RawJingleIq(raw)
        }
    }
}
