package org.ward.android.xmpp

import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smackx.commands.AdHocCommand
import org.jivesoftware.smackx.commands.packet.AdHocCommandData
import org.jivesoftware.smackx.commands.AdHocCommandManager
import org.jivesoftware.smackx.disco.packet.DiscoverItems
import org.jivesoftware.smackx.xdata.FormField
import org.jivesoftware.smackx.xdata.form.FillableForm
import org.jivesoftware.smackx.xdata.packet.DataForm
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

data class AdminCommand(val jid: String, val node: String, val name: String)
data class AdminNote(val type: String, val text: String)
data class AdminField(
    val varName: String, val type: String, val label: String, val desc: String,
    val required: Boolean, val values: List<String>,
    val options: List<Pair<String, String>> = emptyList(), val media: String = ""
)
data class AdminStep(
    val jid: String, val node: String, val sessionId: String = "", val status: String = "executing",
    val title: String = "", val instructions: List<String> = emptyList(),
    val fields: List<AdminField> = emptyList(), val notes: List<AdminNote> = emptyList(),
    val actions: List<String> = emptyList(), val defaultAction: String = "",
    val reported: List<AdminField> = emptyList(), val items: List<List<String>> = emptyList()
) {
    val finished: Boolean get() = status == "completed" || status == "canceled"
}

object AdminNodes {
    private const val A = "http://jabber.org/protocol/admin#"
    val byKey = mapOf(
        "add-user" to A + "add-user", "delete-user" to A + "delete-user", "disable-user" to A + "disable-user",
        "reenable-user" to A + "reenable-user", "end-user-session" to A + "end-user-session",
        "change-user-password" to A + "change-user-password", "get-user-password" to A + "get-user-password",
        "get-user-lastlogin" to A + "get-user-lastlogin", "user-stats" to A + "user-stats",
        "get-registered-users-num" to A + "get-registered-users-num",
        "get-disabled-users-num" to A + "get-disabled-users-num",
        "get-online-users-num" to A + "get-online-users-num", "get-active-users-num" to A + "get-active-users-num",
        "get-registered-users-list" to A + "get-registered-users-list",
        "get-online-users-list" to A + "get-online-users-list", "get-online-users" to A + "get-online-users",
        "announce" to A + "announce", "set-motd" to A + "set-motd", "edit-motd" to A + "edit-motd",
        "delete-motd" to A + "delete-motd", "set-welcome" to A + "set-welcome",
        "restart" to A + "restart", "shutdown" to A + "shutdown",
        "reload" to "http://prosody.im/protocol/modules#reload", "load" to "http://prosody.im/protocol/modules#load",
        "unload" to "http://prosody.im/protocol/modules#unload", "list-modules" to "http://prosody.im/protocol/modules#list",
        "config-reload" to "http://prosody.im/protocol/config#reload",
        "invite" to "urn:xmpp:invite#invite", "create-account" to "urn:xmpp:invite#create-account",
        "uptime" to "uptime", "global-reload" to "http://prosody.im/protocol/modules#global-reload",
        "s2s-list" to "http://prosody.im/protocol/s2s#list"
    )
    fun of(key: String) = byKey[key] ?: key
}

class AdminService(private val connection: XMPPConnection, private val domain: String) {
    private var cached: List<AdminCommand>? = null

    fun listCommands(): List<AdminCommand> {
        cached?.let { return it }
        val mgr = AdHocCommandManager.getAddHocCommandsManager(connection)
        val jid = JidCreate.domainBareFrom(domain)
        val res: DiscoverItems = mgr.discoverCommands(jid)
        val list = res.items.map {
            AdminCommand(it.entityID.toString(), it.node ?: "", it.name ?: (it.node ?: ""))
        }.filter { it.node.isNotBlank() }
            .sortedBy { it.name.lowercase() }
        cached = list
        return list
    }

    fun offers(node: String): Boolean = listCommands().any { it.node == node }

    fun execute(jid: String = domain, node: String): AdminStep {
        val cmd = AdHocCommandData()
        cmd.type = IQ.Type.set
        cmd.to = JidCreate.from(jid)
        cmd.node = node
        cmd.action = AdHocCommand.Action.execute
        val res = connection.sendIqRequestAndWaitForResponse(cmd) as AdHocCommandData
        return parse(jid, node, res)
    }

    fun advance(step: AdminStep, action: String = "", values: Map<String, List<String>>? = null): AdminStep {
        val act = action.ifBlank { step.defaultAction.ifBlank { "complete" } }
        val cmd = AdHocCommandData()
        cmd.type = IQ.Type.set
        cmd.to = JidCreate.from(step.jid)
        cmd.node = step.node
        cmd.sessionID = step.sessionId
        cmd.action = when (act) {
            "next" -> AdHocCommand.Action.next; "prev" -> AdHocCommand.Action.prev
            "cancel" -> AdHocCommand.Action.cancel; "execute" -> AdHocCommand.Action.execute
            else -> AdHocCommand.Action.complete
        }
        if (values != null && act != "cancel" && step.fields.isNotEmpty()) {
            val df = buildSubmit(step, values)
            cmd.form = df
        }
        val res = connection.sendIqRequestAndWaitForResponse(cmd) as AdHocCommandData
        return parse(step.jid, step.node, res)
    }

    private fun buildSubmit(step: AdminStep, values: Map<String, List<String>>): DataForm {
        val b = DataForm.builder(DataForm.Type.submit)
        for (f in step.fields) {
            if (f.type == "fixed" || f.type == "hidden") continue
            val vals = values[f.varName] ?: f.values
            val field: FormField = when (f.type) {
                "boolean" -> FormField.booleanBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .setValue(vals.firstOrNull() == "1" || vals.firstOrNull() == "true").build()
                "jid-multi" -> FormField.jidMultiBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .addValues(vals.map { JidCreate.from(it) }).build()
                "jid-single" -> FormField.jidSingleBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .setValue(vals.firstOrNull()?.let { JidCreate.from(it) }).build()
                "list-multi" -> FormField.listMultiBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .addValues(vals).build()
                "list-single" -> FormField.listSingleBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .setValue(vals.firstOrNull() ?: "").build()
                "text-multi" -> FormField.textMultiBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .addValues(vals).build()
                "text-private" -> FormField.textPrivateBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .setValue(vals.firstOrNull() ?: "").build()
                else -> FormField.textSingleBuilder(f.varName).setLabel(f.label).setDescription(f.desc)
                    .setValue(vals.firstOrNull() ?: "").build()
            }
            b.addField(field)
        }
        val ft = step.fields.firstOrNull { it.varName == "FORM_TYPE" }
        if (ft != null) b.addField(FormField.hiddenBuilder("FORM_TYPE").setValue(ft.values.firstOrNull() ?: "").build())
        return b.build()
    }

    private fun parse(jid: String, node: String, res: AdHocCommandData): AdminStep {
        val status = res.status?.toString() ?: "completed"
        val df = res.form
        val fields = df?.fields?.map { toField(it) } ?: emptyList()
        val notes = res.notes.map { AdminNote(it.type?.toString() ?: "info", it.value ?: "") }
        val actions = res.actions?.map { it.toString() } ?: emptyList()
        val def = res.executeAction?.toString() ?: ""
        return AdminStep(jid, node, res.sessionID ?: "", status, df?.title ?: "",
            df?.instructions ?: emptyList(), fields, notes, actions, def,
            reported = df?.reportedData?.fields?.map { toField(it) } ?: emptyList(),
            items = df?.items?.map { item -> item.fields.map { it.valuesAsString.joinToString(", ") } } ?: emptyList())
    }

    private fun toField(f: FormField): AdminField {
        val type = f.type?.toString() ?: "text-single"
        val options = when (val t = f.type) {
            FormField.Type.list_single -> (f as? org.jivesoftware.smackx.xdata.ListSingleFormField)?.options?.map { it.value.toString() to (it.label?.toString() ?: it.value.toString()) } ?: emptyList()
            FormField.Type.list_multi -> (f as? org.jivesoftware.smackx.xdata.ListMultiFormField)?.options?.map { it.value.toString() to (it.label?.toString() ?: it.value.toString()) } ?: emptyList()
            else -> emptyList()
        }
        var media = ""
        for (child in f.formFieldChildElements) {
            if (child.elementName == "media") {
                // Smack exposes the raw XML of media children; extract base64 data for CAPTCHA images.
                media = Regex(">(data:[^<]+)<").find(child.toXML().toString())?.groupValues?.get(1) ?: ""
            }
        }
        return AdminField(f.variable, type, f.label ?: f.variable, f.description ?: "", f.isRequired,
            f.valuesAsString.toList(), options, media)
    }

    fun stats(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (key in listOf("get-registered-users-num", "get-online-users-num", "get-active-users-num")) {
            if (!offers(AdminNodes.of(key))) continue
            runCatching {
                val step = execute(node = AdminNodes.of(key))
                step.fields.filter { it.varName != "FORM_TYPE" && it.values.isNotEmpty() }.forEach { out[it.varName] = it.values.first() }
                step.notes.forEach { if (it.text.isNotBlank()) out[key] = it.text }
            }
        }
        if (!out.containsKey("onlineusersnum") && offers(AdminNodes.of("get-online-users-list"))) {
            runCatching { out["onlineusersnum"] = userList(AdminNodes.of("get-online-users-list")).size.toString() }
        }
        if (offers(AdminNodes.of("uptime"))) runCatching {
            val step = execute(node = AdminNodes.of("uptime"))
            val text = step.notes.joinToString(" ") { it.text }.ifBlank { step.fields.firstOrNull()?.values?.firstOrNull() ?: "" }
            if (text.isNotBlank()) out["uptime"] = text
        }
        return out
    }

    fun users(): Pair<List<String>, String> {
        if (offers(AdminNodes.of("get-registered-users-list"))) return userList(AdminNodes.of("get-registered-users-list")) to "registered"
        if (offers(AdminNodes.of("get-online-users-list"))) return userList(AdminNodes.of("get-online-users-list")) to "online"
        return emptyList<String>() to ""
    }

    private fun userList(node: String): List<String> {
        var step = execute(node = node)
        if (!step.finished && step.fields.isNotEmpty()) {
            val values = step.fields.associate { it.varName to (if (it.varName == "max_items") listOf("all") else it.values) }
            step = advance(step, "complete", values)
        }
        val out = mutableSetOf<String>()
        step.fields.filter { it.type == "jid-multi" || it.varName.endsWith("userjids") || it.varName.endsWith("users") }
            .forEach { out.addAll(it.values) }
        step.items.flatten().filter { it.contains('@') }.forEach { out.add(it.trim()) }
        return out.sorted()
    }

    fun createInvite(contactInvite: Boolean = false): String {
        val key = if (contactInvite) "invite" else "create-account"
        var step = execute(node = AdminNodes.of(key))
        if (!step.finished && step.fields.isNotEmpty()) step = advance(step, "complete", emptyMap())
        step.fields.firstOrNull { it.varName in listOf("landing-url", "uri", "url") && it.values.isNotEmpty() }?.let {
            return it.values.first()
        }
        step.notes.firstOrNull { it.text.contains("xmpp:") || it.text.contains("https://") }?.let { return it.text.trim() }
        throw IllegalStateException("the server did not return an invite")
    }

    fun cancel(step: AdminStep) {
        if (step.finished || step.sessionId.isBlank()) return
        runCatching { advance(step, "cancel") }
    }
}
