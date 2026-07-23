package com.spixyy.ghostusers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.Utils.createCheckedSetting
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
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
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import com.discord.api.channel.Channel
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.fcm.NotificationRenderer
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.channels.memberlist.GuildMemberListItemGeneratorKt
import com.discord.widgets.channels.memberlist.PrivateChannelMemberListItemGeneratorKt
import com.discord.widgets.channels.memberlist.WidgetChannelMembersListViewModel
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.voice.fullscreen.grid.PrivateCallBlurredGridView
import com.discord.widgets.voice.fullscreen.grid.PrivateCallGridView
import com.discord.widgets.voice.fullscreen.grid.VideoCallGridAdapter
import java.lang.reflect.Modifier
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

    /** StoreMediaSettings instanca (statički getter na StoreStream, potvrđeno u APK 126021). */
    private fun mediaSettings(): Any? = try {
        StoreStream::class.java.methods.firstOrNull {
            it.name == "getMediaSettings" && Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty()
        }?.invoke(null)
    } catch (_: Throwable) { null }

    /** Da li je korisnik već lokalno mutovan — čita iz getMutedUsers(): Map<Long, Boolean>. */
    private fun isUserLocallyMuted(ms: Any, userId: Long): Boolean = try {
        val map = ms.javaClass.methods.firstOrNull { it.name == "getMutedUsers" && it.parameterTypes.isEmpty() }
            ?.invoke(ms) as? Map<*, *>
        map?.get(userId) == true
    } catch (_: Throwable) { false }

    /** Lokalni mute (samo kod tebe). Pravi API u Discord 126021: toggleUserMuted(long) —
        toggluje, pa prvo proverimo stanje i pipnemo samo ako treba. */
    private fun setLocalMute(userId: Long, mute: Boolean) {
        try {
            val ms = mediaSettings() ?: run { logger.warn("GhostUsers: getMediaSettings null — auto-mute preskočen"); return }
            if (isUserLocallyMuted(ms, userId) == mute) return
            val toggle = ms.javaClass.methods.firstOrNull { it.name == "toggleUserMuted" && it.parameterTypes.size == 1 }
            if (toggle != null) toggle.invoke(ms, userId)
            else logger.warn("GhostUsers: toggleUserMuted nije nađen na StoreMediaSettings")
        } catch (e: Throwable) { logger.warn("GhostUsers: setLocalMute — ${e.message}") }
    }

    /* ---------------- sakrivanje učesnika poziva (grid auto-reposition) ----------------
       VideoCallGridAdapter.setData(List) prima finalnu listu pločica. Filtriramo je pre
       ulaska → grid renderuje manje pločica, a ResizingGridLayoutManager sam prerasporedi
       (kao filter VoiceStateStore-a na PC-ju). User id vadimo:
         item.getParticipantData() → ParticipantData → (polje tipa VoiceUser) → getUser() → id
       Napomena: primenjuje se čim je korisnik sakriven, nezavisno od scope prekidača
       (poziv je jedan kontekst; scope-per-kanal za poziv dolazi posle test-a na telefonu). */

    /* ---------------- guild (server) member lista + ONLINE/OFFLINE brojevi ----------------
       generateGuildMemberListItems vraća WidgetChannelMembersListViewModel.MemberList — to je
       INTERFEJS (get(i)/getSize()/getHeaderPositionForItem/getListId). Napravimo filtriran
       snapshot: izbacimo Member redove sakrivenih i umanjimo memberCount njihovog headera.
       Sve u try/catch — na bilo koju grešku vraćamo original (nikad ne slomimo listu). */

    private fun memberItemUserId(item: Any): Long? =
        (item as? ChannelMembersListAdapter.Item.Member)?.userId

    private fun isHeaderItem(item: Any): Boolean =
        item is ChannelMembersListAdapter.Item.Header || item is ChannelMembersListAdapter.Item.RoleHeader

    private fun buildFilteredMemberList(orig: WidgetChannelMembersListViewModel.MemberList):
        WidgetChannelMembersListViewModel.MemberList? {
        return try {
            val n = orig.size
            if (n <= 0 || n > 3000) return null // bezbednosni limit za ogromne servere
            val src = ArrayList<ChannelMembersListAdapter.Item>(n)
            for (i in 0 until n) src.add(orig.get(i))

            val out = ArrayList<ChannelMembersListAdapter.Item>(n)
            var i = 0
            while (i < src.size) {
                val item = src[i]
                if (isHeaderItem(item)) {
                    var j = i + 1
                    val group = ArrayList<ChannelMembersListAdapter.Item>()
                    while (j < src.size && !isHeaderItem(src[j])) { group.add(src[j]); j++ }
                    val kept = group.filter { !isHidden(memberItemUserId(it)) }
                    val removed = group.size - kept.size
                    out.add(if (removed > 0) adjustHeaderCount(item, removed) else item)
                    out.addAll(kept)
                    i = j
                } else {
                    if (!isHidden(memberItemUserId(item))) out.add(item)
                    i++
                }
            }
            if (out.size == src.size) return orig // ništa nije izbačeno

            val headerPos = IntArray(out.size) { -1 }
            var last = -1
            for (k in out.indices) { if (isHeaderItem(out[k])) last = k; headerPos[k] = last }
            val listId = orig.listId

            object : WidgetChannelMembersListViewModel.MemberList {
                override fun get(index: Int): ChannelMembersListAdapter.Item = out[index]
                override fun getSize(): Int = out.size
                override fun getListId(): String = listId
                override fun getHeaderPositionForItem(index: Int): Int? {
                    val h = if (index in out.indices) headerPos[index] else -1
                    return if (h < 0) null else h
                }
            }
        } catch (e: Throwable) { logger.warn("GhostUsers: guild member lista — ${e.message}"); null }
    }

    /** Umanji broj u status headeru (ONLINE — N / OFFLINE — N) za broj izbačenih. */
    private fun adjustHeaderCount(header: Any, removed: Int): ChannelMembersListAdapter.Item {
        return try {
            val h = header as ChannelMembersListAdapter.Item.Header
            val newCount = (h.memberCount - removed).coerceAtLeast(0)
            h.copy(h.component1(), h.component2(), newCount)
        } catch (_: Throwable) { header as ChannelMembersListAdapter.Item }
    }

    // Skupi red na 0 visine + GONE (sakriveno) ili vrati na WRAP_CONTENT (vidljivo).
    // Vertikalna lista se skupi bez rupe; red se reciklira pa ga svaki put postavimo.
    private fun setRowCollapsed(view: View, hide: Boolean) {
        val lp = view.layoutParams
            ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.height = if (hide) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = lp
        view.visibility = if (hide) View.GONE else View.VISIBLE
    }

    // Koliko sakrivenih je u grupi. getMemberCount broji channel.getRecipients() (List<User>),
    // pa i mi brojimo iz istog izvora. Radi bilo da recipients vraća User objekte ili Long id-eve;
    // fallback na recipientIds ako recipients nije dostupan.
    private fun countHiddenRecipients(channel: Channel): Int {
        return try {
            val w = ChannelWrapper(channel)
            val list: List<*> = w.recipients ?: w.recipientIds ?: return 0
            list.count { r ->
                val id = (r as? Number)?.toLong() ?: r?.let { reflectLong(it, "id") }
                id != null && isHidden(id)
            }
        } catch (_: Throwable) { 0 }
    }

    private fun callItemUserId(item: Any): Long? {
        return try {
            val pd = item.javaClass.methods.firstOrNull {
                it.name == "getParticipantData" && it.parameterTypes.isEmpty()
            }?.invoke(item) ?: return null
            val voiceUser = pd.javaClass.declaredFields.firstOrNull { f ->
                f.type.name.contains("StoreVoiceParticipants") && f.type.name.contains("VoiceUser")
            }?.apply { isAccessible = true }?.get(pd)
                ?: pd.javaClass.methods.firstOrNull {
                    it.parameterTypes.isEmpty() && it.returnType.name.contains("VoiceUser")
                }?.invoke(pd)
                ?: return null
            val user = voiceUser.javaClass.methods.firstOrNull {
                it.name == "getUser" && it.parameterTypes.isEmpty()
            }?.invoke(voiceUser) ?: return null
            reflectLong(user, "id")
        } catch (_: Throwable) { null }
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

        // 2b) Notifikacije (sistemska traka + in-app heads-up) od sakrivenih se NE prikazuju
        //     dok je aplikacija živa (u prvom planu ili u pozadini). display()/displayInApp()
        //     primaju NotificationData(getUserId/getChannelId). NAPOMENA: kada je app POTPUNO
        //     ugašen, notifikaciju gura Google (FCM) mimo aplikacije — to plugin ne može da vidi.
        try {
            for (fn in listOf("display", "displayInApp")) {
                val m = NotificationRenderer::class.java.declaredMethods.firstOrNull {
                    it.name == fn && it.parameterTypes.any { p -> p.name.endsWith("NotificationData") }
                } ?: continue
                patcher.patch(m, PreHook { param ->
                    try {
                        val data = param.args.firstOrNull { it != null && it.javaClass.name.endsWith("NotificationData") }
                            ?: return@PreHook
                        val uid = reflectLong(data, "userId")
                        val chId = reflectLong(data, "channelId")
                        if (uid != null && isHiddenIn(uid, chId)) param.result = null
                    } catch (e: Throwable) { logger.warn("GhostUsers: notif filter — ${e.message}") }
                })
            }
        } catch (e: Throwable) { logger.warn("GhostUsers: NotificationRenderer patch nije uspeo — ${e.message}") }

        // 3) Prikaz istorije: umesto brisanja poruka iz store-a (što je pravilo treptaj
        //    "bljesne pa nestane"), sakrivamo RED pri iscrtavanju — poruka sakrivenog se
        //    nikad vizuelno ne pojavi, ni nova ni stara. onConfigure(int, ChatListEntry)
        //    je stabilan hook svakog reda poruke (isti koji koristi zvanični primer).
        try {
            patcher.after<WidgetChatListAdapterItemMessage>(
                "onConfigure",
                Int::class.java,
                ChatListEntry::class.java,
            ) { param ->
                try {
                    val vh = param.thisObject as? RecyclerView.ViewHolder ?: return@after
                    val view = vh.itemView
                    val message = (param.args[1] as? MessageEntry)?.message
                    val hide = message != null && shouldHideAnyMessage(message, reflectLong(message, "channelId"))
                    setRowCollapsed(view, hide)
                } catch (e: Throwable) { logger.warn("GhostUsers: onConfigure — ${e.message}") }
            }
        } catch (e: Throwable) { logger.warn("GhostUsers: chat row patch nije uspeo — ${e.message}") }

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

        // 5b) Poziv: izbaci sakrivene iz grid-a pre nego što ga adapter iscrta →
        //     preostali učesnici se sami prerasporede/centriraju
        try {
            patcher.before<VideoCallGridAdapter>("setData", List::class.java) { param ->
                try {
                    if (hidden.isEmpty()) return@before
                    val list = param.args[0] as? List<*> ?: return@before
                    val filtered = list.filter { it != null && !isHidden(callItemUserId(it)) }
                    if (filtered.size != list.size) param.args[0] = filtered
                } catch (e: Throwable) { logger.warn("GhostUsers: grid filter — ${e.message}") }
            }
        } catch (e: Throwable) { logger.warn("GhostUsers: VideoCallGridAdapter patch nije uspeo — ${e.message}") }

        // 5c) Ringing/preview ekran poziva (pre ulaska u call): krugovi sakrivenih
        //     se uopšte ne crtaju — obe varijante (običan + blurovan grid) primaju
        //     List<StoreVoiceParticipants.VoiceUser> kroz configure(), pa raspored
        //     sam preračuna pozicije za preostale.
        for (gridCls in listOf(PrivateCallGridView::class.java, PrivateCallBlurredGridView::class.java)) {
            try {
                patcher.patch(
                    gridCls.getDeclaredMethod("configure", List::class.java),
                    PreHook { param ->
                        try {
                            if (hidden.isEmpty()) return@PreHook
                            val list = param.args[0] as? List<*> ?: return@PreHook
                            val filtered = list.filter { vu ->
                                val u = (vu as? StoreVoiceParticipants.VoiceUser)?.user
                                u == null || !isHidden(u.id)
                            }
                            if (filtered.size != list.size) param.args[0] = filtered
                        } catch (e: Throwable) { logger.warn("GhostUsers: preview grid — ${e.message}") }
                    },
                )
            } catch (e: Throwable) { logger.warn("GhostUsers: ${gridCls.simpleName} patch nije uspeo — ${e.message}") }
        }

        // 5d) Lista članova grupe (side panel) + "MEMBERS — N": generator dobija
        //     Map<Long, User> kao args[1]; izbacimo sakrivene iz mape pa se i
        //     redovi i broj u headeru sami izračunaju bez njih.
        try {
            val genMethod = PrivateChannelMemberListItemGeneratorKt::class.java.declaredMethods
                .firstOrNull { it.name == "generateGroupDmMemberListItems" }
            if (genMethod == null) {
                logger.warn("GhostUsers: generateGroupDmMemberListItems nije nađen — member lista se ne filtrira")
            } else {
                patcher.patch(genMethod, PreHook { param ->
                    try {
                        if (hidden.isEmpty()) return@PreHook
                        if (!settings.getBool("scopeGroups", true)) return@PreHook
                        val users = param.args[1] as? Map<*, *> ?: return@PreHook
                        val filtered = users.filterKeys { k -> (k as? Long)?.let { !isHidden(it) } ?: true }
                        if (filtered.size != users.size) param.args[1] = filtered
                    } catch (e: Throwable) { logger.warn("GhostUsers: member lista — ${e.message}") }
                })
            }
        } catch (e: Throwable) { logger.warn("GhostUsers: member list patch nije uspeo — ${e.message}") }

        // 5e) Voice korisnici u listi kanala servera: sakriveni se ne prikazuju ispod
        //     voice kanala. WidgetChannelListModel.getItems() vraća sve stavke liste;
        //     izbacimo ChannelListItemVoiceUser sakrivenih pa se lista sama složi.
        try {
            patcher.after<WidgetChannelListModel>("getItems") { param ->
                try {
                    if (hidden.isEmpty() || !settings.getBool("scopeServers", false)) return@after
                    val list = param.result as? List<*> ?: return@after
                    val filtered = list.filter { it !is ChannelListItemVoiceUser || !isHidden(it.user?.id) }
                    if (filtered.size != list.size) param.result = filtered
                } catch (e: Throwable) { logger.warn("GhostUsers: voice u listi kanala — ${e.message}") }
            }
        } catch (e: Throwable) { logger.warn("GhostUsers: WidgetChannelListModel patch nije uspeo — ${e.message}") }

        // 5f) Lista članova servera + ONLINE/OFFLINE brojevi: filtriran wrapper
        try {
            val gm = GuildMemberListItemGeneratorKt::class.java.declaredMethods
                .firstOrNull { it.name == "generateGuildMemberListItems" }
            if (gm == null) logger.warn("GhostUsers: generateGuildMemberListItems nije nađen — server lista se ne filtrira")
            else patcher.patch(gm, Hook { param ->
                try {
                    if (hidden.isEmpty() || !settings.getBool("scopeServers", false)) return@Hook
                    val orig = param.result as? WidgetChannelMembersListViewModel.MemberList ?: return@Hook
                    buildFilteredMemberList(orig)?.let { param.result = it }
                } catch (e: Throwable) { logger.warn("GhostUsers: server member lista — ${e.message}") }
            })
        } catch (e: Throwable) { logger.warn("GhostUsers: guild member list patch nije uspeo — ${e.message}") }

        // 5g) DM lista (Direct Messages): "N Members" ispod grupe → umanji za broj sakrivenih.
        //     getMemberCount(Channel, Context) vraća gotov string ("7 Members"); prepišemo broj.
        try {
            val m = WidgetChannelsListAdapter.ItemChannelPrivate::class.java
                .getDeclaredMethod("getMemberCount", Channel::class.java, Context::class.java)
                .apply { isAccessible = true }
            patcher.patch(m, Hook { param ->
                try {
                    if (hidden.isEmpty() || !settings.getBool("scopeGroups", true)) return@Hook
                    val channel = param.args[0] as? Channel ?: return@Hook
                    val hiddenCount = countHiddenRecipients(channel)
                    if (hiddenCount <= 0) return@Hook
                    val s = param.result as? String ?: return@Hook
                    val match = Regex("\\d+").find(s, 0) ?: return@Hook // eksplicitni startIndex (stdlib 1.5.21)
                    val n = match.value.toIntOrNull() ?: return@Hook
                    val newN = if (n - hiddenCount < 0) 0 else n - hiddenCount
                    param.result = s.substring(0, match.range.first) + newN + s.substring(match.range.last + 1)
                } catch (e: Throwable) { logger.warn("GhostUsers: DM member count — ${e.message}") }
            })
        } catch (e: Throwable) { logger.warn("GhostUsers: getMemberCount patch nije uspeo — ${e.message}") }

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
