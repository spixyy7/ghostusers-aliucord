package com.spixyy.ghostusers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.Utils.createCheckedSetting
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.aliucord.utils.GsonUtils
import com.aliucord.widgets.BottomSheet
import com.aliucord.wrappers.ChannelWrapper
import com.discord.models.domain.ModelMessageDelete
import com.discord.stores.StoreCallsIncoming
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreNotifications
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import com.lytefast.flexinput.R
import java.lang.reflect.Field
import com.discord.api.message.Message as ApiMessage
import com.discord.models.domain.ModelCall

/**
 * GhostUsers za Aliucord — port BetterDiscord plugina.
 *
 * Lokalno (samo kod tebe) sakriva izabrane osobe u grupnim DM-ovima:
 *  - njihove poruke (nove + istorija), bez notifikacija
 *  - tuđe poruke koje ih taguju ili reply-uju (opcija)
 *  - "X is typing..." (best-effort, reflektivno)
 *  - dolazno zvono za 1-na-1 poziv od sakrivenog (kada je uključen scope "1-na-1 DM")
 *  - automatski lokalni mute u pozivima (best-effort, reflektivno)
 *
 * Dugme za sakrivanje: otvori profil korisnika (tap na avatar) → "Sakrij (Ghost)".
 * Podešavanja: Settings → Plugins → GhostUsers.
 */
@Suppress("unused")
@SuppressLint("SetTextI18n")
@AliucordPlugin(requiresRestart = false)
class GhostUsers : Plugin() {
    private data class Entry(val id: Long, val name: String)

    /** id -> ime (samo za prikaz u podešavanjima) */
    private val hidden = LinkedHashMap<Long, String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        settingsTab = SettingsTab(GhostSettings::class.java, SettingsTab.Type.BOTTOM_SHEET)
            .withArgs(this)
    }

    /* ---------------- reflektivni pristup ApiMessage poljima ----------------
       Discord APK čuva imena POLJA (gson), dok su metode obfuskovane — zato
       polja čitamo po imenu, sa fallback varijantama i bez pucanja ako nema. */

    private fun apiField(vararg names: String): Field? {
        for (n in names) try {
            return ApiMessage::class.java.getDeclaredField(n).apply { isAccessible = true }
        } catch (_: Throwable) {}
        logger.warn("GhostUsers: ApiMessage polje ${names.joinToString("/")} nije nađeno — ta provera je isključena")
        return null
    }

    private val fId by lazy { apiField("id") }
    private val fChannelId by lazy { apiField("channelId", "channel_id") }
    private val fAuthor by lazy { apiField("author") }
    private val fContent by lazy { apiField("content") }
    private val fMentions by lazy { apiField("mentions") }
    private val fRefMsg by lazy { apiField("referencedMessage", "referenced_message") }

    private fun reflectObj(obj: Any, vararg names: String): Any? {
        for (n in names) {
            try {
                val f = obj.javaClass.getDeclaredField(n).apply { isAccessible = true }
                f.get(obj)?.let { return it }
            } catch (_: Throwable) {}
            try {
                val getter = "get" + n.replaceFirstChar { it.uppercase() }
                val m = obj.javaClass.methods.firstOrNull { it.name == getter && it.parameterTypes.isEmpty() }
                m?.invoke(obj)?.let { return it }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun reflectLong(obj: Any, vararg names: String): Long? {
        for (n in names) {
            try {
                val f = obj.javaClass.getDeclaredField(n).apply { isAccessible = true }
                (f.get(obj) as? Number)?.let { return it.toLong() }
            } catch (_: Throwable) {}
            try {
                val getter = "get" + n.replaceFirstChar { it.uppercase() }
                val m = obj.javaClass.methods.firstOrNull { it.name == getter && it.parameterTypes.isEmpty() }
                (m?.invoke(obj) as? Number)?.let { return it.toLong() }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun apiAuthorId(msg: ApiMessage): Long? =
        fAuthor?.get(msg)?.let { reflectLong(it, "id") }

    private fun apiChannelId(msg: ApiMessage): Long? =
        (fChannelId?.get(msg) as? Number)?.toLong()

    private fun apiMessageId(msg: ApiMessage): Long? =
        (fId?.get(msg) as? Number)?.toLong()

    /* ---------------- core logika ---------------- */

    private fun isHidden(userId: Long?) = userId != null && hidden.containsKey(userId)

    private fun channelType(channelId: Long): Int? = try {
        val store = StoreStream.getChannels()
        val m = store.javaClass.methods.firstOrNull {
            it.name == "getChannel" && it.parameterTypes.size == 1
        }
        val ch = m?.invoke(store, channelId)
        if (ch != null) ChannelWrapper(ch as com.discord.api.channel.Channel).type else null
    } catch (_: Throwable) { null }

    /** U kojoj vrsti kanala važi sakrivanje: grupni DM (default) / serveri / 1-na-1 DM. */
    private fun scopeAllows(channelId: Long?): Boolean {
        val g = settings.getBool("scopeGroups", true)
        val srv = settings.getBool("scopeServers", false)
        val dm = settings.getBool("scopeDMs", false)
        if (g && srv && dm) return true
        val t = channelId?.let { channelType(it) } ?: return false
        return when (t) {
            3 -> g   // GROUP_DM
            1 -> dm  // 1-na-1 DM
            else -> srv // svi ostali tipovi = kanali servera
        }
    }

    private fun isHiddenIn(userId: Long?, channelId: Long?): Boolean =
        isHidden(userId) && scopeAllows(channelId)

    /** Poruka sakrivenog, ili (opciono) bilo čija koja ga taguje / reply-uje. */
    private fun shouldHideApi(msg: ApiMessage, chIdOverride: Long? = null): Boolean {
        val chId = chIdOverride ?: apiChannelId(msg)
        apiAuthorId(msg)?.let { if (isHiddenIn(it, chId)) return true }
        if (!settings.getBool("hideMentions", true)) return false

        (fMentions?.get(msg) as? List<*>)?.forEach { u ->
            if (u != null && isHiddenIn(reflectLong(u, "id"), chId)) return true
        }
        (fRefMsg?.get(msg) as? ApiMessage)?.let { ref ->
            apiAuthorId(ref)?.let { if (isHiddenIn(it, chId)) return true }
        }
        val content = fContent?.get(msg) as? String
        if (content != null && content.contains("<@"))
            for (id in hidden.keys)
                if (isHiddenIn(id, chId) && (content.contains("<@$id>") || content.contains("<@!$id>")))
                    return true
        return false
    }

    /** Isto kao shouldHideApi, ali generički (refleksijom) — radi i za MODEL poruke
        (com.discord.models.message.Message) koje vraća handleMessagesLoaded chunk. */
    private fun shouldHideAnyMessage(msg: Any, chId: Long?): Boolean {
        reflectObj(msg, "author")?.let { a ->
            reflectLong(a, "id")?.let { if (isHiddenIn(it, chId)) return true }
        }
        if (!settings.getBool("hideMentions", true)) return false
        (reflectObj(msg, "mentions") as? List<*>)?.forEach { u ->
            val uid = (u as? Number)?.toLong() ?: u?.let { reflectLong(it, "id") }
            if (uid != null && isHiddenIn(uid, chId)) return true
        }
        reflectObj(msg, "referencedMessage")?.let { ref ->
            reflectObj(ref, "author")?.let { a ->
                reflectLong(a, "id")?.let { if (isHiddenIn(it, chId)) return true }
            }
        }
        val content = reflectObj(msg, "content") as? String
        if (content != null && content.contains("<@"))
            for (id in hidden.keys)
                if (isHiddenIn(id, chId) && (content.contains("<@$id>") || content.contains("<@!$id>")))
                    return true
        return false
    }

    /* ---------------- persistencija ---------------- */

    private fun load() {
        hidden.clear()
        try {
            val json = settings.getString("hiddenUsers", "[]")
            GsonUtils.fromJson(json, Array<Entry>::class.java)?.forEach { hidden[it.id] = it.name }
        } catch (e: Throwable) { logger.error("GhostUsers: load settings", e) }
    }

    private fun save() {
        try {
            settings.setString("hiddenUsers", GsonUtils.toJson(hidden.map { Entry(it.key, it.value) }.toTypedArray()))
        } catch (e: Throwable) { logger.error("GhostUsers: save settings", e) }
    }

    fun hiddenEntries(): List<Pair<Long, String>> = hidden.map { it.key to it.value }

    fun toggleUser(id: Long, name: String) {
        if (hidden.containsKey(id)) {
            hidden.remove(id)
            if (settings.getBool("autoMute", true)) setLocalMute(id, false)
            Utils.showToast("$name — ponovo vidljiv/a")
        } else {
            hidden[id] = name
            if (settings.getBool("autoMute", true)) setLocalMute(id, true)
            Utils.showToast("$name — sakriven/a")
        }
        save()
    }

    fun isUserHidden(id: Long) = hidden.containsKey(id)

    /* ---------------- lokalni voice mute (best-effort, reflektivno) ----------------
       Isto kao dugme "Mute" na korisniku u pozivu — samo kod tebe. Ako se interni
       API razlikuje, samo se loguje upozorenje, ništa ne puca. */

    private fun setLocalMute(userId: Long, mute: Boolean) {
        try {
            val getter = StoreStream::class.java.methods.firstOrNull { it.name == "getMediaSettings" }
            val ms = getter?.invoke(null)
            if (ms == null) { logger.warn("GhostUsers: StoreMediaSettings nedostupan — auto-mute preskočen"); return }

            val isMuted = ms.javaClass.methods.firstOrNull {
                (it.name == "isUserMuted" || it.name == "isMuted") && it.parameterTypes.size == 1
            }?.invoke(ms, userId) as? Boolean
            if (isMuted == mute) return

            val setter2 = ms.javaClass.methods.firstOrNull {
                it.name == "setUserMuted" && it.parameterTypes.size == 2
            }
            if (setter2 != null) { setter2.invoke(ms, userId, mute); return }

            val toggle = ms.javaClass.methods.firstOrNull {
                (it.name == "toggleUserMute" || it.name == "toggleMute") && it.parameterTypes.size == 1
            }
            if (toggle != null) { toggle.invoke(ms, userId); return }

            logger.warn("GhostUsers: nijedna mute metoda nije nađena na StoreMediaSettings")
        } catch (e: Throwable) { logger.warn("GhostUsers: setLocalMute nije uspeo — ${e.message}") }
    }

    /* ---------------- lifecycle ---------------- */

    override fun start(context: Context) {
        load()

        // 1) Nove poruke ne ulaze u store → nema prikaza nigde
        patcher.before<StoreStream>("handleMessageCreate", ApiMessage::class.java) { param ->
            val msg = param.args[0] as ApiMessage
            if (shouldHideApi(msg)) param.result = null
        }
        patcher.before<StoreStream>("handleMessageUpdate", ApiMessage::class.java) { param ->
            val msg = param.args[0] as ApiMessage
            if (shouldHideApi(msg)) param.result = null
        }

        // 2) In-app notifikacije/zvuk za njihove poruke — ubijeno na izvoru
        patcher.before<StoreNotifications>("handleMessageCreate", ApiMessage::class.java) { param ->
            val msg = param.args[0] as ApiMessage
            if (shouldHideApi(msg)) param.result = null
        }

        // 3) Istorija: posle učitavanja chunk-a, lokalno "obriši" poruke sakrivenih
        //    (isti mehanizam kao HideMessages plugin — ne šalje ništa Discordu)
        patcher.after<StoreStream>("handleMessagesLoaded", StoreMessagesLoader.ChannelChunk::class.java) { param ->
            try {
                val chunk = param.args[0] as StoreMessagesLoader.ChannelChunk
                val msgs = chunk.messages ?: return@after
                val toDelete = ArrayList<Pair<Long, Long>>()
                for (msg in msgs) {
                    if (msg == null) continue
                    val chId = reflectLong(msg, "channelId") ?: reflectLong(chunk, "channelId") ?: continue
                    val mId = reflectLong(msg, "id") ?: continue
                    if (shouldHideAnyMessage(msg, chId)) toDelete.add(chId to mId)
                }
                if (toDelete.isEmpty()) return@after
                mainHandler.postDelayed({
                    try {
                        for ((chId, mId) in toDelete)
                            StoreStream.getMessages().handleMessageDelete(ModelMessageDelete(chId, mId))
                    } catch (e: Throwable) { logger.error("GhostUsers: sweep", e) }
                }, 350)
            } catch (e: Throwable) { logger.error("GhostUsers: handleMessagesLoaded", e) }
        }

        // 4) "X is typing..." — reflektivno (ime handler metode se traži u runtime-u)
        try {
            val typingStore = StoreStream.getUsersTyping()
            val methods = typingStore.javaClass.declaredMethods.filter { it.name.startsWith("handleTypingStart") }
            if (methods.isEmpty()) logger.warn("GhostUsers: typing handler nije nađen — typing se ne filtrira")
            for (m in methods) {
                m.isAccessible = true
                patcher.patch(m, PreHook { param ->
                    val model = param.args.getOrNull(0) ?: return@PreHook
                    val uid = reflectLong(model, "userId")
                    val chId = reflectLong(model, "channelId")
                    if (isHiddenIn(uid, chId)) param.result = null
                })
            }
        } catch (e: Throwable) { logger.warn("GhostUsers: typing patch nije uspeo — ${e.message}") }

        // 5) Dolazno zvono: 1-na-1 poziv od sakrivenog se ne prikazuje
        //    (grupni pozivi zvone normalno — inicijator se iz eventa ne vidi)
        try {
            patcher.patch(
                StoreCallsIncoming::class.java.getDeclaredMethod("handleCallCreateOrUpdate", ModelCall::class.java),
                PreHook { param ->
                    try {
                        val call = param.args[0] as ModelCall
                        val chId = call.channelId
                        val store = StoreStream.getChannels()
                        val m = store.javaClass.methods.firstOrNull { it.name == "getChannel" && it.parameterTypes.size == 1 }
                        val ch = m?.invoke(store, chId) as? com.discord.api.channel.Channel ?: return@PreHook
                        val w = ChannelWrapper(ch)
                        if (w.type == 1) { // 1-na-1 DM
                            val other = w.recipientIds.firstOrNull()
                            if (other != null && isHiddenIn(other, chId)) param.result = null
                        }
                    } catch (e: Throwable) { logger.warn("GhostUsers: ring check — ${e.message}") }
                },
            )
        } catch (e: Throwable) { logger.warn("GhostUsers: StoreCallsIncoming patch nije uspeo — ${e.message}") }

        // 6) Dugme na profilu korisnika (user sheet): Sakrij / Prikaži (Ghost)
        patcher.after<WidgetUserSheet>(
            "configureNote",
            WidgetUserSheetViewModel.ViewState.Loaded::class.java,
        ) { param ->
            try {
                val state = param.args[0] as WidgetUserSheetViewModel.ViewState.Loaded
                val user = state.user ?: return@after
                val userId = user.id
                val me = try { StoreStream.getUsers().me.id } catch (_: Throwable) { -1L }
                if (userId == me) return@after

                val binding = WidgetUserSheet.`access$getBinding$p`(this)
                val root = binding.a
                val noteHeader = root.findViewById<View>(noteHeaderId) ?: return@after
                val layout = noteHeader.parent as? LinearLayout ?: return@after

                var btn = layout.findViewById<TextView>(ghostBtnId)
                if (btn == null) {
                    btn = TextView(layout.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = ghostBtnId
                        setCompoundDrawablesWithIntrinsicBounds(hideIconId, 0, 0, 0)
                    }
                    layout.addView(btn, layout.indexOfChild(noteHeader))
                }
                fun refreshLabel() {
                    btn.text = if (isUserHidden(userId)) "Prikaži korisnika (Ghost)" else "Sakrij korisnika (Ghost)"
                }
                refreshLabel()
                btn.setOnClickListener {
                    toggleUser(userId, user.username ?: userId.toString())
                    refreshLabel()
                }
            } catch (e: Throwable) { logger.error("GhostUsers: user sheet dugme", e) }
        }

        // 7) /ghost komanda — brza lista sakrivenih iz bilo kog chata
        commands.registerCommand("ghost", "Lista sakrivenih korisnika (GhostUsers)") {
            val list = hiddenEntries()
            val text = if (list.isEmpty()) "Niko nije sakriven."
            else "Sakriveni:\n" + list.joinToString("\n") { (id, name) -> "• $name ($id)" }
            CommandsAPI.CommandResult(text, null, false)
        }

        // startni auto-mute za sve već sakrivene
        if (settings.getBool("autoMute", true))
            for (id in hidden.keys) setLocalMute(id, true)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }

    companion object {
        private val ghostBtnId = View.generateViewId()
        private val noteHeaderId = Utils.getResId("user_sheet_note_header", "id")
        private val hideIconId = Utils.getResId("drawable_chip_delete", "drawable")
    }
}

/** Podešavanja: Settings → Plugins → GhostUsers */
@SuppressLint("SetTextI18n")
class GhostSettings(private val plugin: GhostUsers) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx = view.context
        val settings: SettingsAPI = plugin.settings

        addView(
            createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, "Grupni DM-ovi", "Sakrivanje važi u grupnim DM-ovima").apply {
                isChecked = settings.getBool("scopeGroups", true)
                setOnCheckedListener { settings.setBool("scopeGroups", it) }
            },
        )
        addView(
            createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, "Serveri", "Sakrivanje važi i na serverima").apply {
                isChecked = settings.getBool("scopeServers", false)
                setOnCheckedListener { settings.setBool("scopeServers", it) }
            },
        )
        addView(
            createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, "1-na-1 DM-ovi", "Sakrivanje važi i u direktnim porukama").apply {
                isChecked = settings.getBool("scopeDMs", false)
                setOnCheckedListener { settings.setBool("scopeDMs", it) }
            },
        )
        addView(
            createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, "Sakrij tagove i reply-e", "I tuđe poruke koje pominju sakrivenog").apply {
                isChecked = settings.getBool("hideMentions", true)
                setOnCheckedListener { settings.setBool("hideMentions", it) }
            },
        )
        addView(
            createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, "Auto mute u pozivu", "Lokalni mute sakrivenih (unhide ga uvek skida)").apply {
                isChecked = settings.getBool("autoMute", true)
                setOnCheckedListener { settings.setBool("autoMute", it) }
            },
        )

        val entries = plugin.hiddenEntries()
        addView(TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_Settings_Item).apply {
            text = if (entries.isEmpty()) "Niko nije sakriven — otvori profil korisnika i tapni \"Sakrij korisnika (Ghost)\"."
            else "Sakriveni (tap za vraćanje):"
        })
        for ((id, name) in entries) {
            addView(TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_Settings_Item_Icon).apply {
                text = "• $name ($id)"
                setOnClickListener {
                    plugin.toggleUser(id, name)
                    dismiss()
                }
            })
        }
    }
}
