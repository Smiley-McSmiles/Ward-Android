package org.ward.android.xmpp

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class JingleCandidate(val foundation: String, val component: Int, val ip: String, val port: Int,
                           val priority: Long, val type: String, val protocol: String = "udp")

data class JingleMedia(
    val mid: String, val media: String, val payloads: List<Triple<Int, String, Int>>,
    val ufrag: String, val pwd: String, val fingerprint: String, val setup: String,
    val candidates: List<JingleCandidate>, val senders: String = "both"
)

object JingleSdp {
    const val NS_JINGLE = "urn:xmpp:jingle:1"
    const val NS_RTP = "urn:xmpp:jingle:apps:rtp:1"
    const val NS_ICE = "urn:xmpp:jingle:transports:ice-udp:1"
    const val NS_DTLS = "urn:xmpp:jingle:apps:dtls:0"
    const val NS_GROUP = "urn:xmpp:jingle:apps:grouping:0"
    const val NS_JMI = "urn:xmpp:jingle-message:0"

    fun sdpToJingle(sdp: String, sid: String, action: String, initiator: String, responder: String? = null): String {
        val media = parseSdp(sdp)
        val contents = StringBuilder()
        for (m in media) {
            val sender = if (action == "session-accept") "both" else "both"
            // The responder accepts the content the initiator created; creator stays "initiator"
            // for both session-initiate and session-accept. Using "responder" here makes strict
            // Jingle implementations reject the accept.
            contents.append("<content creator='initiator' name='${esc(m.mid)}' senders='$sender'>")
            contents.append("<description xmlns='$NS_RTP' media='${m.media}'>")
            for ((id, name, clock) in m.payloads) {
                val channels = if (name == "opus") " channels='2'" else ""
                contents.append("<payload-type id='$id' name='${esc(name)}' clockrate='$clock'$channels/>")
            }
            contents.append("<rtcp-mux/>")
            for ((id, _) in m.payloads.withIndex()) { /* rtcp-fb omitted; WebRTC negotiates defaults */ }
            contents.append("</description>")
            contents.append("<transport xmlns='$NS_ICE' ufrag='${esc(m.ufrag)}' pwd='${esc(m.pwd)}'>")
            contents.append("<fingerprint xmlns='$NS_DTLS' hash='sha-256' setup='${esc(m.setup)}'>${esc(m.fingerprint.replace("sha-256 ", ""))}</fingerprint>")
            for (c in m.candidates) {
                contents.append("<candidate component='${c.component}' foundation='${esc(c.foundation)}' generation='0' id='${esc(c.foundation + c.component)}' ip='${esc(c.ip)}' network='0' port='${c.port}' priority='${c.priority}' protocol='${c.protocol}' type='${esc(c.type)}'/>")
            }
            contents.append("</transport></content>")
        }
        // WebRTC is configured with MAXBUNDLE. Even an audio-only offer/answer needs the group,
        // otherwise libwebrtc rejects the other side's description.
        val group = "<group xmlns='$NS_GROUP' semantics='BUNDLE'>" +
            media.joinToString("") { "<content name='${esc(it.mid)}'/>" } + "</group>"
        val attrs = StringBuilder("xmlns='$NS_JINGLE' action='$action' sid='${esc(sid)}' initiator='${esc(initiator)}'")
        if (responder != null) attrs.append(" responder='${esc(responder)}'")
        return "<jingle $attrs>$contents$group</jingle>"
    }

    fun extractJingle(xml: String): String? {
        val start = xml.indexOf("<jingle")
        if (start < 0) return null
        val end = xml.indexOf("</jingle>", start)
        return if (end >= 0) xml.substring(start, end + 9) else xml.substring(start)
    }

    fun jingleToSdp(jingleXmlFull: String, answering: Boolean): String? = runCatching {
        val jingleXml = extractJingle(jingleXmlFull) ?: jingleXmlFull
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(jingleXml.toByteArray()))
        val root = doc.documentElement
        if (root.localName != "jingle") return null
        val contents = root.getElementsByTagNameNS(NS_JINGLE, "content")
        if (contents.length == 0) return null
        val medias = mutableListOf<JingleMedia>()
        val mids = mutableListOf<String>()
        for (i in 0 until contents.length) {
            val c = contents.item(i) as Element
            val mid = c.getAttribute("name").ifBlank { i.toString() }
            val desc = first(c, NS_RTP, "description") ?: continue
            val media = desc.getAttribute("media").ifBlank { "audio" }
            val payloads = mutableListOf<Triple<Int, String, Int>>()
            val pts = desc.getElementsByTagNameNS(NS_RTP, "payload-type")
            for (j in 0 until pts.length) {
                val p = pts.item(j) as Element
                val id = p.getAttribute("id").toIntOrNull() ?: continue
                val name = p.getAttribute("name")
                val clock = p.getAttribute("clockrate").toIntOrNull() ?: 48000
                if (name.isNotBlank()) payloads.add(Triple(id, name, clock))
            }
            val tr = first(c, NS_ICE, "transport")
            val fp = tr?.let { first(it, NS_DTLS, "fingerprint") }
            val ufrag = tr?.getAttribute("ufrag") ?: ""
            val pwd = tr?.getAttribute("pwd") ?: ""
            val candidates = mutableListOf<JingleCandidate>()
            tr?.getElementsByTagNameNS(NS_ICE, "candidate")?.let { cs ->
                for (j in 0 until cs.length) {
                    val e = cs.item(j) as Element
                    candidates.add(JingleCandidate(e.getAttribute("foundation"), e.getAttribute("component").toIntOrNull() ?: 1,
                        e.getAttribute("ip"), e.getAttribute("port").toIntOrNull() ?: 0,
                        e.getAttribute("priority").toLongOrNull() ?: 0, e.getAttribute("type").ifBlank { "host" },
                        e.getAttribute("protocol").ifBlank { "udp" }))
                }
            }
            medias.add(JingleMedia(mid, media, payloads, ufrag, pwd,
                fp?.textContent ?: "", fp?.getAttribute("setup") ?: (if (answering) "active" else "actpass"),
                candidates, c.getAttribute("senders").ifBlank { "both" }))
            mids.add(mid)
        }
        if (medias.isEmpty()) return null
        val group = first(root, NS_GROUP, "group")
        val bundleMids = mutableListOf<String>()
        group?.getElementsByTagNameNS(NS_GROUP, "content")?.let { nodes ->
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as? Element)?.getAttribute("name") ?: continue
                if (name.isNotBlank() && name !in bundleMids) bundleMids.add(name)
            }
        }
        val bundleOrder = if (bundleMids.isNotEmpty()) bundleMids else mids
        val sb = StringBuilder()
        sb.append("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
        sb.append("a=group:BUNDLE ${bundleOrder.joinToString(" ")}\r\n")
        sb.append("a=msid-semantic: WMS ward\r\n")
        for (m in medias) {
            val payloadIds = m.payloads.joinToString(" ") { it.first.toString() }.ifBlank { if (m.media == "audio") "111 0 8" else "96" }
            sb.append("m=${m.media} 9 UDP/TLS/RTP/SAVPF $payloadIds\r\n")
            sb.append("c=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n")
            sb.append("a=ice-ufrag:${m.ufrag}\r\na=ice-pwd:${m.pwd}\r\na=ice-options:trickle\r\n")
            sb.append("a=fingerprint:sha-256 ${m.fingerprint}\r\n")
            sb.append("a=setup:${m.setup.ifBlank { if (answering) "active" else "actpass" }}\r\n")
            sb.append("a=mid:${m.mid}\r\na=sendrecv\r\na=rtcp-mux\r\n")
            for ((id, name, clock) in m.payloads) {
                sb.append("a=rtpmap:$id $name/$clock")
                if (name == "opus") sb.append("/2")
                sb.append("\r\n")
            }
            if (m.payloads.isEmpty() && m.media == "audio") {
                sb.append("a=rtpmap:111 opus/48000/2\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\n")
            }
            if (m.payloads.isEmpty() && m.media == "video") {
                sb.append("a=rtpmap:96 VP8/90000\r\na=rtpmap:97 H264/90000\r\n")
            }
            for (c in m.candidates) {
                sb.append("a=candidate:${c.foundation} ${c.component} ${c.protocol} ${c.priority} ${c.ip} ${c.port} typ ${c.type}\r\n")
            }
            sb.append("a=end-of-candidates\r\n")
        }
        sb.toString()
    }.getOrNull()

    private fun first(parent: Element, ns: String, name: String): Element? {
        val list = parent.getElementsByTagNameNS(ns, name)
        return if (list.length > 0) list.item(0) as Element else null
    }

    private fun parseSdp(sdp: String): List<JingleMedia> {
        val lines = sdp.replace("\r\n", "\n").split('\n')
        var sessionFingerprint = ""
        var sessionSetup = "actpass"
        val out = mutableListOf<JingleMedia>()
        var currentMedia = ""
        var mid = ""
        var ufrag = ""
        var pwd = ""
        var fingerprint = ""
        var setup = "actpass"
        val payloads = mutableListOf<Triple<Int, String, Int>>()
        val candidates = mutableListOf<JingleCandidate>()
        fun flush() {
            if (currentMedia.isNotBlank()) out.add(JingleMedia(mid.ifBlank { out.size.toString() }, currentMedia,
                payloads.toList(), ufrag, pwd, fingerprint.ifBlank { sessionFingerprint },
                if (setup.isBlank()) sessionSetup else setup, candidates.toList()))
        }
        for (line in lines) {
            when {
                line.startsWith("m=") -> {
                    flush(); payloads.clear(); candidates.clear()
                    val parts = line.removePrefix("m=").split(' ')
                    currentMedia = parts.firstOrNull() ?: "audio"
                    mid = ""; ufrag = ""; pwd = ""; fingerprint = ""; setup = ""
                }
                line.startsWith("a=mid:") -> mid = line.removePrefix("a=mid:")
                line.startsWith("a=ice-ufrag:") -> ufrag = line.removePrefix("a=ice-ufrag:")
                line.startsWith("a=ice-pwd:") -> pwd = line.removePrefix("a=ice-pwd:")
                line.startsWith("a=setup:") -> setup = line.removePrefix("a=setup:")
                line.startsWith("a=fingerprint:") -> {
                    val f = line.removePrefix("a=fingerprint:"); fingerprint = f.substringAfter(' ')
                    if (currentMedia.isBlank()) sessionFingerprint = fingerprint
                }
                line.startsWith("a=rtpmap:") -> {
                    val rest = line.removePrefix("a=rtpmap:"); val id = rest.substringBefore(' ').toIntOrNull()
                    val codec = rest.substringAfter(' '); val name = codec.substringBefore('/')
                    val clock = codec.substringAfter('/', "48000").substringBefore('/').toIntOrNull() ?: 48000
                    if (id != null) payloads.add(Triple(id, name, clock))
                }
                line.startsWith("a=candidate:") -> {
                    val p = line.removePrefix("a=candidate:").split(' ')
                    if (p.size >= 8) candidates.add(JingleCandidate(p[0], p[1].toIntOrNull() ?: 1,
                        p[4], p[5].toIntOrNull() ?: 0, p[3].toLongOrNull() ?: 0, p[7], p[2]))
                }
            }
        }
        flush()
        return out
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "&apos;").replace("\"", "&quot;")
}
