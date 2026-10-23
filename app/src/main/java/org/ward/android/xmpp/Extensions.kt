package org.ward.android.xmpp

import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.XmlStringBuilder

object Ns {
    const val MARKDOWN = "urn:xmpp:ward:markdown:0"
    const val REACTIONS = "urn:xmpp:reactions:0"
    const val REPLY = "urn:xmpp:reply:0"
    const val FALLBACK = "urn:xmpp:fallback:0"
    const val CORRECT = "urn:xmpp:message-correct:0"
    const val RETRACT = "urn:xmpp:message-retract:1"
    const val RETRACT_0 = "urn:xmpp:message-retract:0"
    const val FASTEN = "urn:xmpp:fasten:0"
    const val MODERATE = "urn:xmpp:message-moderate:1"
    const val RECEIPTS = "urn:xmpp:receipts"
    const val MARKERS = "urn:xmpp:chat-markers:0"
    const val CHATSTATES = "http://jabber.org/protocol/chatstates"
    const val HINTS = "urn:xmpp:hints"
    const val OOB = "jabber:x:oob"
    const val SFS = "urn:xmpp:sfs:0"
    const val FILE = "urn:xmpp:file:metadata:0"
    const val THUMBS = "urn:xmpp:thumbs:1"
    const val HASHES = "urn:xmpp:hashes:2"
    const val OCCUPANT_ID = "urn:xmpp:occupant-id:0"
    const val REFERENCE = "urn:xmpp:reference:0"
    const val SPOILER = "urn:xmpp:spoiler:0"
    const val OMEMO2 = "urn:xmpp:omemo:2"
    const val OMEMO = "eu.siacs.conversations.axolotl"
    const val OPENPGP = "urn:xmpp:openpgp:0"
    const val EME = "urn:xmpp:eme:0"
    const val MUC_USER = "http://jabber.org/protocol/muc#user"
    const val CONFERENCE = "jabber:x:conference"
    const val SID = "urn:xmpp:sid:0"
    const val DELAY = "urn:xmpp:delay"
    const val MAM = "urn:xmpp:mam:2"
    const val COMMANDS = "http://jabber.org/protocol/commands"
    const val ADMIN = "http://jabber.org/protocol/admin#"
    const val DISCO_ITEMS = "http://jabber.org/protocol/disco#items"
    const val DISCO_INFO = "http://jabber.org/protocol/disco#info"
}

class RawExtension(private val elementName: String, private val namespace: String,
                   val xml: String) : ExtensionElement {
    override fun getElementName() = elementName
    override fun getNamespace() = namespace
    override fun toXML(env: org.jivesoftware.smack.packet.XmlEnvironment?): CharSequence =
        XmlStringBuilder().append(xml)
}

fun Message.ext(name: String, ns: String): StandardExtensionElement? =
    getExtension(name, ns) as? StandardExtensionElement

fun initExtensionProviders() {
    val namespaces = listOf(
        Ns.MARKDOWN to "markdown", Ns.REACTIONS to "reactions", Ns.REPLY to "reply",
        Ns.FALLBACK to "fallback", Ns.CORRECT to "replace", Ns.RETRACT to "retract",
        Ns.RETRACT_0 to "retract", Ns.FASTEN to "apply-to", Ns.MODERATE to "moderate",
        Ns.SFS to "file-sharing", Ns.FILE to "file", Ns.THUMBS to "thumbnail", Ns.HASHES to "hash",
        Ns.REFERENCE to "reference", Ns.SPOILER to "spoiler", Ns.OCCUPANT_ID to "occupant-id",
        Ns.EME to "encryption", Ns.OPENPGP to "openpgp", Ns.OMEMO2 to "encrypted"
    )
    for ((ns, name) in namespaces) {
        if (ProviderManager.getExtensionProvider(name, ns) == null) {
            ProviderManager.addExtensionProvider(name, ns, StandardExtensionElementProvider())
        }
    }
    // file metadata children have different element names
    for (n in listOf("media-type", "name", "size", "width", "height", "length", "desc", "sources", "url-data")) {
        if (ProviderManager.getExtensionProvider(n, Ns.FILE) == null)
            ProviderManager.addExtensionProvider(n, Ns.FILE, StandardExtensionElementProvider())
        if (ProviderManager.getExtensionProvider(n, Ns.SFS) == null)
            ProviderManager.addExtensionProvider(n, Ns.SFS, StandardExtensionElementProvider())
    }
}

fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;")

fun Message.addMarkdown() = addExtension(RawExtension("markdown", Ns.MARKDOWN,
    "<markdown xmlns='${Ns.MARKDOWN}'/>"))

fun Message.addCorrection(id: String) = addExtension(RawExtension("replace", Ns.CORRECT,
    "<replace xmlns='${Ns.CORRECT}' id='${esc(id)}'/>"))

fun Message.addRetraction(id: String) {
    addExtension(RawExtension("retract", Ns.RETRACT,
        "<retract xmlns='${Ns.RETRACT}' id='${esc(id)}'/>"))
    addExtension(RawExtension("apply-to", Ns.FASTEN,
        "<apply-to xmlns='${Ns.FASTEN}' id='${esc(id)}'><retract xmlns='${Ns.RETRACT_0}'/></apply-to>"))
    addExtension(RawExtension("fallback", Ns.FALLBACK,
        "<fallback xmlns='${Ns.FALLBACK}' for='${Ns.RETRACT}'/>"))
}

fun Message.addReactions(id: String, emojis: List<String>) {
    val inner = emojis.joinToString("") { "<reaction>${esc(it)}</reaction>" }
    addExtension(RawExtension("reactions", Ns.REACTIONS,
        "<reactions xmlns='${Ns.REACTIONS}' id='${esc(id)}'>$inner</reactions>"))
}

fun Message.addReply(toJid: String, id: String) {
    val to = if (toJid.isNotBlank()) " to='${esc(toJid)}'" else ""
    addExtension(RawExtension("reply", Ns.REPLY,
        "<reply xmlns='${Ns.REPLY}'$to id='${esc(id)}'/>"))
}

fun Message.addReplyFallback(length: Int) {
    addExtension(RawExtension("fallback", Ns.FALLBACK,
        "<fallback xmlns='${Ns.FALLBACK}' for='${Ns.REPLY}'><body start='0' end='$length'/></fallback>"))
}

fun Message.addHint(hint: String) = addExtension(RawExtension(hint, Ns.HINTS,
    "<$hint xmlns='${Ns.HINTS}'/>"))

fun Message.addOriginId(id: String) = addExtension(RawExtension("origin-id", Ns.SID,
    "<origin-id xmlns='${Ns.SID}' id='${esc(id)}'/>"))

fun Message.addChatState(state: String) = addExtension(RawExtension(state, Ns.CHATSTATES,
    "<$state xmlns='${Ns.CHATSTATES}'/>"))

fun Message.addMarker(kind: String, id: String) = addExtension(RawExtension(kind, Ns.MARKERS,
    "<$kind xmlns='${Ns.MARKERS}' id='${esc(id)}'/>"))

fun Message.addMarkable() = addExtension(RawExtension("markable", Ns.MARKERS,
    "<markable xmlns='${Ns.MARKERS}'/>"))

fun Message.addEmojiEncryption(namespace: String, name: String) = addExtension(RawExtension("encryption", Ns.EME,
    "<encryption xmlns='${Ns.EME}' namespace='${esc(namespace)}' name='${esc(name)}'/>"))

fun Message.addFileSharing(url: String, name: String, mime: String, size: Long, encrypted: Boolean = false,
                           width: Int = 0, height: Int = 0, thumbnailB64: String = "", sha256: String = "") {
    val f = StringBuilder()
    f.append("<file-sharing xmlns='${Ns.SFS}' disposition='inline'>")
    f.append("<file xmlns='${Ns.FILE}'>")
    if (mime.isNotBlank()) f.append("<media-type>${esc(mime)}</media-type>")
    f.append("<name>${esc(name)}</name>")
    f.append("<size>$size</size>")
    if (width > 0) f.append("<width>$width</width>")
    if (height > 0) f.append("<height>$height</height>")
    if (sha256.isNotBlank()) f.append("<hash xmlns='${Ns.HASHES}' algo='sha-256'>${esc(sha256)}</hash>")
    if (thumbnailB64.isNotBlank()) f.append("<thumbnail xmlns='${Ns.THUMBS}' uri='data:image/jpeg;base64,${thumbnailB64}' media-type='image/jpeg'/>")
    f.append("</file>")
    f.append("<sources><url-data xmlns='http://jabber.org/protocol/url-data' target='${esc(url)}'/></sources>")
    f.append("</file-sharing>")
    addExtension(RawExtension("file-sharing", Ns.SFS, f.toString()))
    // OOB for older clients
    addExtension(RawExtension("x", Ns.OOB, "<x xmlns='${Ns.OOB}'><url>${esc(url)}</url><desc>${esc(name)}</desc></x>"))
    if (encrypted) addEmojiEncryption("urn:xmpp:omemo:2", "OMEMO")
}

data class ParsedAttachments(val url: String, val name: String, val mime: String, val size: Long,
                             val width: Int, val height: Int, val thumbnail: String = "")

fun Message.attachments(): List<ParsedAttachments> {
    val out = mutableListOf<ParsedAttachments>()
    val sfs = ext("file-sharing", Ns.SFS)
    if (sfs != null) {
        val file = sfs.getFirstElement("file", Ns.FILE)
        val sources = sfs.getFirstElement("sources", Ns.SFS)
        val url = sources?.getFirstElement("url-data", "http://jabber.org/protocol/url-data")?.getAttributeValue("target")
            ?: ""
        if (url.isNotBlank()) out.add(ParsedAttachments(url,
            file?.getFirstElement("name", Ns.FILE)?.text ?: url.substringAfterLast('/'),
            file?.getFirstElement("media-type", Ns.FILE)?.text ?: "",
            file?.getFirstElement("size", Ns.FILE)?.text?.toLongOrNull() ?: 0,
            file?.getFirstElement("width", Ns.FILE)?.text?.toIntOrNull() ?: 0,
            file?.getFirstElement("height", Ns.FILE)?.text?.toIntOrNull() ?: 0,
            file?.getFirstElement("thumbnail", Ns.THUMBS)?.getAttributeValue("uri") ?: ""))
    }
    if (out.isEmpty()) {
        val bodyText = body ?: ""
        val aes = Regex("aesgcm://\\S+").find(bodyText)
        if (aes != null) {
            val url = aes.value
            val name = bodyText.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("aesgcm://") }
                ?: url.substringAfterLast('/').substringBefore('#')
            out.add(ParsedAttachments(url, name, guessMime(name), 0, 0, 0))
        }
        val oob = ext("x", Ns.OOB)
        if (oob != null) {
            val url = oob.getFirstElement("url", Ns.OOB)?.text ?: oob.getFirstElement("url")?.text
            if (!url.isNullOrBlank()) out.add(ParsedAttachments(url,
                oob.getFirstElement("desc", Ns.OOB)?.text ?: url.substringAfterLast('/'), "", 0, 0, 0))
        }
    }
    return out
}

fun attachmentsFromBody(body: String): List<ParsedAttachments> {
    val aes = Regex("aesgcm://\\S+").find(body) ?: return emptyList()
    val url = aes.value
    val name = body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("aesgcm://") }
        ?: url.substringAfterLast('/').substringBefore('#')
    return listOf(ParsedAttachments(url, name, guessMime(name), 0, 0, 0))
}

fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
        "webp" -> "image/webp"; "heic" -> "image/heic"; "mp4" -> "video/mp4"
        "m4a", "aac" -> "audio/mp4"; "ogg", "oga", "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"; "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

// Overloads for Smack's builder API, so outgoing stanzas can be assembled fluently.
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addMarkdown() =
    addExtension(RawExtension("markdown", Ns.MARKDOWN, "<markdown xmlns='${Ns.MARKDOWN}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addOriginId(id: String) =
    addExtension(RawExtension("origin-id", Ns.SID, "<origin-id xmlns='${Ns.SID}' id='${esc(id)}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addMarkable() =
    addExtension(RawExtension("markable", Ns.MARKERS, "<markable xmlns='${Ns.MARKERS}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addChatState(state: String) =
    addExtension(RawExtension(state, Ns.CHATSTATES, "<$state xmlns='${Ns.CHATSTATES}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addHint(hint: String) =
    addExtension(RawExtension(hint, Ns.HINTS, "<$hint xmlns='${Ns.HINTS}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addMarker(kind: String, id: String) =
    addExtension(RawExtension(kind, Ns.MARKERS, "<$kind xmlns='${Ns.MARKERS}' id='${esc(id)}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addCorrection(id: String) =
    addExtension(RawExtension("replace", Ns.CORRECT, "<replace xmlns='${Ns.CORRECT}' id='${esc(id)}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addReply(toJid: String, id: String) {
    val to = if (toJid.isNotBlank()) " to='${esc(toJid)}'" else ""
    addExtension(RawExtension("reply", Ns.REPLY, "<reply xmlns='${Ns.REPLY}'$to id='${esc(id)}'/>"))
}
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addReplyFallback(length: Int) =
    addExtension(RawExtension("fallback", Ns.FALLBACK, "<fallback xmlns='${Ns.FALLBACK}' for='${Ns.REPLY}'><body start='0' end='$length'/></fallback>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addReactions(id: String, emojis: List<String>) {
    val inner = emojis.joinToString("") { "<reaction>${esc(it)}</reaction>" }
    addExtension(RawExtension("reactions", Ns.REACTIONS, "<reactions xmlns='${Ns.REACTIONS}' id='${esc(id)}'>$inner</reactions>"))
}
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addRetraction(id: String) {
    addExtension(RawExtension("retract", Ns.RETRACT, "<retract xmlns='${Ns.RETRACT}' id='${esc(id)}'/>"))
    addExtension(RawExtension("apply-to", Ns.FASTEN,
        "<apply-to xmlns='${Ns.FASTEN}' id='${esc(id)}'><retract xmlns='${Ns.RETRACT_0}'/></apply-to>"))
    addExtension(RawExtension("fallback", Ns.FALLBACK, "<fallback xmlns='${Ns.FALLBACK}' for='${Ns.RETRACT}'/>"))
}
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addEmojiEncryption(namespace: String, name: String) =
    addExtension(RawExtension("encryption", Ns.EME,
        "<encryption xmlns='${Ns.EME}' namespace='${esc(namespace)}' name='${esc(name)}'/>"))
fun org.jivesoftware.smack.packet.StanzaBuilder<*>.addFileSharing(url: String, name: String, mime: String, size: Long,
        encrypted: Boolean = false, width: Int = 0, height: Int = 0, thumbnailB64: String = "", sha256: String = "") {
    val f = StringBuilder()
    f.append("<file-sharing xmlns='${Ns.SFS}' disposition='inline'>")
    f.append("<file xmlns='${Ns.FILE}'>")
    if (mime.isNotBlank()) f.append("<media-type>${esc(mime)}</media-type>")
    f.append("<name>${esc(name)}</name><size>$size</size>")
    if (width > 0) f.append("<width>$width</width>")
    if (height > 0) f.append("<height>$height</height>")
    if (sha256.isNotBlank()) f.append("<hash xmlns='${Ns.HASHES}' algo='sha-256'>${esc(sha256)}</hash>")
    if (thumbnailB64.isNotBlank()) f.append("<thumbnail xmlns='${Ns.THUMBS}' uri='data:image/jpeg;base64,${thumbnailB64}' media-type='image/jpeg'/>")
    f.append("</file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='${esc(url)}'/></sources></file-sharing>")
    addExtension(RawExtension("file-sharing", Ns.SFS, f.toString()))
    addExtension(RawExtension("x", Ns.OOB, "<x xmlns='${Ns.OOB}'><url>${esc(url)}</url><desc>${esc(name)}</desc></x>"))
    if (encrypted) addEmojiEncryption("urn:xmpp:omemo:2", "OMEMO")
    return
}
