package org.ward.android.xmpp

import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jxmpp.jid.parts.Localpart

object Registration {
    fun createAccount(domain: String, username: String, password: String) {
        val cfg = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(domain)
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
            .setConnectTimeout(30_000)
            .build()
        val conn = XMPPTCPConnection(cfg)
        try {
            conn.connect()
            val mgr = AccountManager.getInstance(conn)
            if (!mgr.supportsAccountCreation()) throw IllegalStateException("This server does not allow creating accounts from apps")
            mgr.createAccount(Localpart.from(username), password)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
