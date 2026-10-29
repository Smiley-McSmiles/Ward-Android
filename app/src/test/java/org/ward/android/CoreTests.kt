package org.ward.android

import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.graphics.Color
import org.ward.android.data.SettingsSchema
import org.ward.android.ui.HighlightPalette
import org.ward.android.ui.highlightCode
import org.ward.android.ui.shouldAutoDownload
import org.ward.android.util.FileCrypto
import androidx.compose.ui.text.AnnotatedString
import org.ward.android.xmpp.ExternalServicesIq
import org.ward.android.xmpp.JingleIqs
import org.ward.android.xmpp.Omemo2
import org.ward.android.xmpp.OmemoBackup
import org.ward.android.xmpp.JingleSdp
import org.ward.android.xmpp.RawJingleIq
import org.ward.android.xmpp.OpenPgp
import org.ward.android.xmpp.attachments
import org.ward.android.xmpp.attachmentsFromBody
import org.ward.android.xmpp.bodyWithoutAttachmentUrls
import org.ward.android.xmpp.guessMime
import org.ward.android.updates.UpdateCheck
import java.io.File

class CoreTests {
    @Test fun syntaxHighlightingMarksKotlinAndXmlTokens() {
        val palette = HighlightPalette(
            plain = Color.Black, keyword = Color.Red, string = Color.Green, comment = Color.Gray,
            number = Color.Yellow, function = Color.Blue, type = Color.Cyan,
            tag = Color.Magenta, attribute = Color.DarkGray, punctuation = Color.LightGray
        )
        val kotlin = highlightCode("fun main() {\n  val s = \"hi\" // note\n}", "kotlin", palette)
        fun AnnotatedString.has(text: String, color: Color): Boolean = spanStyles.any {
            it.item.color == color && it.start < it.end && substring(it.start, it.end) == text
        }
        assertTrue(kotlin.has("fun", palette.keyword))
        assertTrue(kotlin.has("val", palette.keyword))
        assertTrue(kotlin.has("\"hi\"", palette.string))
        assertTrue(kotlin.has("// note", palette.comment))

        val xml = highlightCode("<tag attr=\"x\">value</tag>", "xml", palette)
        assertTrue(xml.has("tag", palette.tag))
        assertTrue(xml.has("attr", palette.attribute))
        assertTrue(xml.has("\"x\"", palette.string))
    }

    @Test fun unknownAttachmentSizeDoesNotAutoDownloadOnMeteredConnections() {
        val oneMiB = 1024L * 1024L
        assertFalse(shouldAutoDownload(false, false, 0L, oneMiB, false))
        assertFalse(shouldAutoDownload(false, false, 57L * 1024 * 1024, oneMiB, false))
        assertTrue(shouldAutoDownload(false, false, 500L * 1024, oneMiB, false))
        assertTrue(shouldAutoDownload(false, false, 0L, oneMiB, true))
        assertTrue(shouldAutoDownload(true, false, 0L, oneMiB, false))
        assertTrue(shouldAutoDownload(false, true, 0L, oneMiB, false))
    }

    @Test fun onlyFinishedAttachmentDownloadsCountAsCached() {
        val url = "aesgcm://upload.example/f.enc#00ff"
        val finished = FileCrypto.cacheName(url, "clip.mp4")
        // A second viewer may know the same URL under a different display name.
        val otherName = FileCrypto.cacheName(url, "clip")
        assertTrue(FileCrypto.isCompleteCacheFile(finished, url, 20L * 1024 * 1024))
        assertTrue(FileCrypto.isCompleteCacheFile(otherName, url, 20L * 1024 * 1024))
        // In-progress transfers must never be reused, or a 20 MB file looks downloaded halfway.
        assertFalse(FileCrypto.isCompleteCacheFile("$finished.download", url, 10L * 1024 * 1024))
        assertFalse(FileCrypto.isCompleteCacheFile("$finished.part", url, 10L * 1024 * 1024))
        assertFalse(FileCrypto.isCompleteCacheFile(finished, url, 0L))
        assertFalse(FileCrypto.isCompleteCacheFile(finished, "https://other.example/x", 1L))
    }

    @Test fun settingsHaveOneHomeAndNoDeadControls() {
        val keys = SettingsSchema.specs.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val pages = SettingsSchema.pages.map { it.first }.toSet()
        assertTrue(SettingsSchema.specs.all { it.page in pages })

        val groups = SettingsSchema.specs.groupBy { it.page }.mapValues { (_, v) -> v.map { it.group }.toSet() }
        assertEquals(setOf("Calls", "Audio", "Network", "Sounds"), groups["calls"])
        assertEquals(setOf("Sending", "Receiving"), groups["attachments"])
        assertEquals(setOf("Photos", "Strangers"), groups["privacy"])
        assertEquals(setOf("Screen privacy"), groups["security"])
        assertTrue("Background" in groups["general"].orEmpty())
        assertTrue("Encryption" in groups["encryption"].orEmpty())

        // Controls removed from Android because they never had an effect here.
        val removed = listOf("general.tray", "general.confirm_quit", "chats.toolbar",
            "appearance.message_font", "calls.mic_gain", "storage.cache_limit",
            "advanced.ip_preference")
        for (key in removed) assertFalse(key, SettingsSchema.specs.any { it.key == key })
    }

    @Test fun mediaUrlsInMessageBodyBecomeAttachments() {
        val body = "Look at this\nhttps://upload.example.org/file.mp4?token=abc"
        val atts = attachmentsFromBody(body)
        assertEquals(1, atts.size)
        assertEquals("video/mp4", atts[0].mime)
        assertEquals("file.mp4", atts[0].name)
        assertEquals("Look at this", bodyWithoutAttachmentUrls(body, atts))

        val encrypted = attachmentsFromBody("Clip\naesgcm://upload.example.org/clip.mp4.enc#abcdef")
        assertEquals(1, encrypted.size)
        assertEquals("video/mp4", encrypted[0].mime)
        assertEquals("Clip", bodyWithoutAttachmentUrls("Clip\naesgcm://upload.example.org/clip.mp4.enc#abcdef", encrypted))
    }

    @Test fun updateCheckerComparesDottedVersions() {
        assertTrue(UpdateCheck.isNewer("1.0.10", "1.0.9"))
        assertTrue(UpdateCheck.isNewer("1.1.0", "1.0.99"))
        assertTrue(UpdateCheck.isNewer("v1.0.5", "1.0.4"))
        assertFalse(UpdateCheck.isNewer("1.0.5", "1.0.5"))
        assertFalse(UpdateCheck.isNewer("1.0.4", "1.0.5"))
    }

    @Test fun updateCheckIsOnByDefault() {
        assertEquals(true, SettingsSchema.byKey["general.update_check"]?.default)
    }

    @Test fun ordinaryLinksAndAudioFormats() {
        assertTrue(attachmentsFromBody("https://example.org/article").isEmpty())
        assertEquals("audio/mpeg", guessMime("https://example.org/song.mp3"))
        assertEquals("video/x-matroska", guessMime("https://example.org/film.mkv"))
        assertEquals("video/webm", guessMime("https://example.org/clip.webm"))
    }
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
        assertTrue(iq.xml.contains("xmlns='urn:xmpp:jingle:apps:rtp:1'"))
        val sdp = JingleSdp.jingleToSdp(iq.xml, false)
        assertNotNull(sdp)
        assertTrue(sdp!!.contains("a=mid:audio0"))
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

    @Test fun omemoSeedAndGtkFingerprintConversion() {
        val seed = ByteArray(32) { it.toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-512").digest(seed)
        val priv = digest.copyOfRange(0, 32).also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = ((it[31].toInt() and 127) or 64).toByte()
        }
        assertEquals("3894eea49c580aef816935762be049559d6d1440dede12e6a125f1841fff8e6f",
            priv.joinToString("") { "%02x".format(it) })
        assertEquals("4701d08488451f545a409fb58ae3e58581ca40ac3f7f114698cd71deac73ca01",
            Omemo2.curveFingerprintFromEdHex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"))
        assertEquals("4701d08488451f545a409fb58ae3e58581ca40ac3f7f114698cd71deac73ca01",
            Omemo2.curveFingerprintFromEdHex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"))
    }

    @Test fun gtkBackupSealRoundTrip() {
        val blob = OmemoBackup.seal(mapOf("entries" to listOf(mapOf("jid" to "bob@example.org"))),
            "correct horse battery staple", "trust")
        val payload = OmemoBackup.unseal(blob, "correct horse battery staple", "trust")
        assertEquals("bob@example.org", ((payload["entries"] as List<*>).first() as Map<*, *>)["jid"])
        try {
            OmemoBackup.unseal(blob, "wrong", "trust")
            fail("wrong passphrase must fail")
        } catch (_: Exception) { }
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
