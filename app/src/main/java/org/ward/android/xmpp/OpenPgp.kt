package org.ward.android.xmpp

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.ward.android.WardCore
import org.ward.android.data.AccountEntity
import org.ward.android.data.PgpKeyEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date

object OpenPgp {
    const val NS = "urn:xmpp:openpgp:0"
    const val NODE_META = "$NS:public-keys"
    private const val NODE_KEY = "$NS:public-keys"
    private const val MAX_SKEW = 7 * 24 * 3600

    fun secretFile(core: WardCore, accountId: Long) = File(File(core.context.filesDir, "pgp").apply { mkdirs() }, "$accountId.asc")

    fun loadSecret(core: WardCore, accountId: Long): PGPSecretKeyRing? = runCatching {
        val f = secretFile(core, accountId)
        if (!f.exists()) null else PGPainless.readKeyRing().secretKeyRing(f.readText())
    }.getOrNull()

    fun ensureKey(core: WardCore, account: AccountEntity): PGPSecretKeyRing {
        loadSecret(core, account.id)?.let { return it }
        val secret = PGPainless.generateKeyRing().modernKeyRing("xmpp:${account.jid}", null as String?)
        secretFile(core, account.id).writeText(PGPainless.asciiArmor(secret))
        return secret
    }

    fun fingerprint(key: PGPSecretKeyRing): String =
        PGPainless.inspectKeyRing(key).fingerprint.toString().replace(" ", "").uppercase()

    fun publicArmor(key: PGPSecretKeyRing): String =
        PGPainless.asciiArmor(PGPainless.extractCertificate(key))

    fun parsePublic(armored: String): PGPPublicKeyRing? = runCatching {
        PGPainless.readKeyRing().publicKeyRing(armored)
    }.getOrNull()

    fun encrypt(secret: PGPSecretKeyRing, recipients: List<PGPPublicKeyRing>, plaintext: ByteArray): ByteArray {
        require(recipients.isNotEmpty()) { "No OpenPGP keys for the recipient" }
        val encryption = EncryptionOptions().addRecipients(recipients)
        val signing = SigningOptions().addInlineSignature(SecretKeyRingProtector.unprotectedKeys(), secret)
        val options = ProducerOptions.signAndEncrypt(encryption, signing).setAsciiArmor(false)
        val out = ByteArrayOutputStream()
        val stream = PGPainless.encryptAndOrSign().onOutputStream(out).withOptions(options)
        stream.write(plaintext)
        stream.close()
        return out.toByteArray()
    }

    fun decrypt(secret: PGPSecretKeyRing, certs: List<PGPPublicKeyRing>, data: ByteArray): ByteArray {
        val opts = ConsumerOptions().addDecryptionKey(secret, SecretKeyRingProtector.unprotectedKeys()).apply {
            for (c in certs) addVerificationCert(c)
        }
        val stream = PGPainless.decryptAndOrVerify().onInputStream(ByteArrayInputStream(data)).withOptions(opts)
        return stream.use { it.readBytes() }
    }

    fun signcryptXml(body: String, toJid: String): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        val rpad = (1..80).map { ('a' + (it * 7 % 26)) }.joinToString("")
        return "<signcrypt xmlns='$NS'><to jid='${esc(toJid)}'/><time stamp='$stamp'/><rpad>$rpad</rpad><payload><body xmlns='jabber:client'>${esc(body)}</body></payload></signcrypt>"
    }

    fun parseSigncrypt(xml: String, expectedTo: String): String? {
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) return null
        return runCatching {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
            val root = doc.documentElement
            if (root.localName != "signcrypt" || root.namespaceURI != NS) return null
            val tos = root.getElementsByTagNameNS(NS, "to")
            var addressed = false
            for (i in 0 until tos.length) {
                val jid = (tos.item(i) as org.w3c.dom.Element).getAttribute("jid").substringBefore('/')
                if (jid.equals(expectedTo, true)) addressed = true
            }
            if (!addressed) return null
            val time = root.getElementsByTagNameNS(NS, "time")
            if (time.length > 0) {
                val stamp = (time.item(0) as org.w3c.dom.Element).getAttribute("stamp")
                val parsed = runCatching { java.time.Instant.parse(stamp).epochSecond }.getOrNull()
                if (parsed != null && parsed - System.currentTimeMillis() / 1000 > MAX_SKEW) return null
            }
            val bodies = root.getElementsByTagNameNS("jabber:client", "body")
            if (bodies.length > 0) (bodies.item(0) as org.w3c.dom.Element).textContent else null
        }.getOrNull()
    }
}
