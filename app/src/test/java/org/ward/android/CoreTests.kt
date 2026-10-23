package org.ward.android

import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Assert.*
import org.junit.Test
import org.ward.android.util.FileCrypto
import org.ward.android.xmpp.ExternalServicesIq
import org.ward.android.xmpp.JingleIqs
import org.ward.android.xmpp.JingleSdp
import org.ward.android.xmpp.RawJingleIq
import org.ward.android.xmpp.OpenPgp
import org.ward.android.xmpp.attachments
import java.io.File

class CoreTests {
    @Test fun aesgcmFileRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val src = File(dir, "hello.txt").apply { writeText("Ward encrypted file test") }
        val enc = File(dir, "hello.enc")
        val (key, iv) = FileCrypto.encrypt(src, enc)
        val out = File(dir, "hello.out")
        FileCrypto.decrypt(enc, out, key, iv)
        assertEquals("Ward encrypted file test", out.readText())
        val link = FileCrypto.aesgcmUrl("https://example.org/files/abc", iv, key)
        val parsed = FileCrypto.parse(link)
        assertNotNull(parsed)
        assertEquals("https://example.org/files/abc", parsed!!.httpsUrl)
        assertArrayEquals(key, parsed.key)
        assertArrayEquals(iv, parsed.iv)
    }

    @Test fun jingleSdpRoundTripKeepsMediaAndCandidates() {
        val sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n" +
            "a=group:BUNDLE 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\n" +
            "a=ice-ufrag:abc\r\na=ice-pwd:def\r\na=fingerprint:sha-256 AA:BB:CC\r\n" +
            "a=setup:actpass\r\na=mid:0\r\na=sendrecv\r\na=rtcp-mux\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n" +
            "a=candidate:1 1 udp 2130706431 192.168.1.2 9999 typ host\r\n"
        val jingle = JingleSdp.sdpToJingle(sdp, "sid", "session-initiate", "alice@example.org/phone", null)
        assertTrue(jingle.contains("session-initiate"))
        assertTrue(jingle.contains("initiator='alice@example.org/phone'"))
        assertTrue(jingle.contains("opus"))
        assertTrue(jingle.contains("192.168.1.2"))
        val back = JingleSdp.jingleToSdp(jingle, false)
        assertNotNull(back)
        assertTrue(back!!.contains("m=audio"))
        assertTrue(back.contains("a=mid:0"))
        assertTrue(back.contains("a=ice-ufrag:abc"))
        assertTrue(back.contains("a=candidate:"))
        // MAXBUNDLE needs the group even when there is only one content.
        assertTrue(jingle.contains("<group xmlns='urn:xmpp:jingle:apps:grouping:0'"))
        assertTrue(back.contains("a=group:BUNDLE 0"))
        assertTrue(back.contains("a=setup:actpass"))
    }

    @Test fun jmiIdComesFromCallElementNotMessageId() {
        val msg: Message = PacketParserUtils.parseStanza(
            "<message xmlns='jabber:client' from='bob@example.org/gtk' to='alice@example.org/phone' " +
                "type='chat' id='message-id'><ringing xmlns='urn:xmpp:jingle-message:0' id='call-id'/></message>")
        val element = msg.extensions.firstOrNull { it.namespace == "urn:xmpp:jingle-message:0" }
            as? StandardExtensionElement
        assertNotNull(element)
        assertEquals("call-id", element!!.getAttributeValue("id"))
        assertNotEquals("message-id", element.getAttributeValue("id"))
    }

    @Test fun jingleProviderKeepsBundleGroup() {
        JingleIqs.registerProvider()
        val iq: IQ = PacketParserUtils.parseStanza(
            "<iq xmlns='jabber:client' type='set' id='j1' from='bob@example.org/gtk'>" +
                "<jingle xmlns='urn:xmpp:jingle:1' action='session-initiate' sid='call' initiator='bob@example.org/gtk'>" +
                "<content creator='initiator' name='audio0'><description xmlns='urn:xmpp:jingle:apps:rtp:1' media='audio'/>" +
                "<transport xmlns='urn:xmpp:jingle:transports:ice-udp:1'/></content>" +
                "<group xmlns='urn:xmpp:jingle:apps:grouping:0' semantics='BUNDLE'><content name='audio0'/></group>" +
                "</jingle></iq>")
        assertTrue(iq is RawJingleIq)
        assertTrue((iq as RawJingleIq).xml.contains("semantics='BUNDLE'"))
    }

    @Test fun externalServiceDiscoveryIqKeepsServicesPayload() {
        ExternalServicesIq.register()
        val iq: IQ = PacketParserUtils.parseStanza(
            "<iq type='result' id='disco' from='example.org'>" +
                "<services xmlns='urn:xmpp:extdisco:2'>" +
                "<service type='turn' host='turn.example.org' port='3478' username='u' password='p'/>" +
                "</services></iq>")
        assertTrue(iq is ExternalServicesIq)
        val payload = (iq as ExternalServicesIq).rawXml
        assertTrue(payload.contains("turn.example.org"))
        assertTrue(payload.contains("services"))
        val request = ExternalServicesIq().toXML(org.jivesoftware.smack.packet.XmlEnvironment.EMPTY).toString()
        assertTrue(request.contains("<services xmlns='urn:xmpp:extdisco:2'"))
    }

    @Test fun bodylessMessageDoesNotCrashAttachmentParsing() {
        val msg: Message = StanzaBuilder.buildMessage().build()
        assertNull(msg.body)
        assertTrue(msg.attachments().isEmpty())
    }

    @Test fun openPgpRoundTrip() {
        val alice = org.pgpainless.PGPainless.generateKeyRing().modernKeyRing("xmpp:alice@example.org", null as String?)
        val bob = org.pgpainless.PGPainless.generateKeyRing().modernKeyRing("xmpp:bob@example.org", null as String?)
        val bobPublic = OpenPgp.parsePublic(OpenPgp.publicArmor(bob))!!
        val alicePublic = OpenPgp.parsePublic(OpenPgp.publicArmor(alice))!!
        val plain = "<signcrypt><payload><body>hello</body></payload></signcrypt>".toByteArray()
        val encrypted = OpenPgp.encrypt(alice, listOf(bobPublic), plain)
        val decrypted = OpenPgp.decrypt(bob, listOf(alicePublic), encrypted)
        assertEquals(String(plain), String(decrypted))
    }
}
