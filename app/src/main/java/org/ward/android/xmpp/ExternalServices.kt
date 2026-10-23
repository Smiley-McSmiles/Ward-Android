package org.ward.android.xmpp

import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.IQProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser

/**
 * XEP-0215 external service discovery. Smack has no manager for this, and its generic IQ
 * parser turns the <services> payload into an UnparsedIQ, so keep the element as raw XML.
 */
class ExternalServicesIq(private val raw: String = "") : IQ("services", NS) {
    companion object {
        const val NS = "urn:xmpp:extdisco:2"

        @Volatile private var registered = false

        fun register() {
            if (registered) return
            synchronized(ExternalServicesIq::class.java) {
                if (registered) return
                ProviderManager.removeIQProvider("services", NS)
                ProviderManager.addIQProvider("services", NS, object : IQProvider<ExternalServicesIq>() {
                    override fun parse(parser: XmlPullParser, initialDepth: Int, xmlEnvironment: XmlEnvironment): ExternalServicesIq {
                        return ExternalServicesIq(PacketParserUtils.parseElement(parser).toString())
                    }
                })
                registered = true
            }
        }
    }

    val rawXml: String get() = raw

    override fun getIQChildElementBuilder(builder: IQ.IQChildElementXmlStringBuilder): IQ.IQChildElementXmlStringBuilder {
        if (raw.isBlank()) {
            builder.rightAngleBracket()
            return builder
        }
        val open = raw.substringAfter('<')
        val attrs = open.substringBefore('>')
        val inner = raw.substringAfter('>').substringBeforeLast("</services>")
        Regex("([a-z]+)=['\"]([^'\"]*)['\"]").findAll(attrs).forEach { m ->
            if (m.groupValues[1] != "xmlns") builder.attribute(m.groupValues[1], m.groupValues[2])
        }
        builder.rightAngleBracket()
        builder.append(inner)
        return builder
    }
}
