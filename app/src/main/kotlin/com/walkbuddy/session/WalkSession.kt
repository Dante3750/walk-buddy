package com.walkbuddy.session

import android.content.Context
import com.walkbuddy.data.AppRepository
import com.walkbuddy.data.Settings
import com.walkbuddy.data.SettingsStore
import com.walkbuddy.data.StepRecorder
import com.walkbuddy.health.HealthBridge
import com.walkbuddy.notify.Notifications
import com.walkbuddy.rtc.PeerLink
import com.walkbuddy.rtc.SignalingClient
import com.walkbuddy.rtc.SignalingState
import com.walkbuddy.sensors.LocationSource
import com.walkbuddy.sensors.StepSource
import com.walkbuddy.domain.CoupleMode
import com.walkbuddy.domain.DecodeResult
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.Highlights
import com.walkbuddy.domain.HighlightsCard
import com.walkbuddy.domain.JoinLink
import com.walkbuddy.domain.MessageCodec
import com.walkbuddy.domain.NudgeConfig
import com.walkbuddy.domain.PeerMessage
import com.walkbuddy.domain.PingLimiter
import com.walkbuddy.domain.SessionCode
import com.walkbuddy.domain.SignalingMessage
import com.walkbuddy.domain.WalkConfig
import com.walkbuddy.domain.WalkEngine
import com.walkbuddy.domain.WalkState
import com.walkbuddy.domain.WalkSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { Idle, Lobby, Walking, Summary }

data class LobbyPeer(val id: String, val name: String, val connected: Boolean)

data class SessionUi(
    val phase: Phase = Phase.Idle,
    val code: String? = null,
    val joinLink: String? = null,
    val signaling: SignalingState = SignalingState.Idle,
    val note: String? = null,
    val peers: List<LobbyPeer> = emptyList(),
    val walk: WalkState? = null,
    val quiet: Boolean = false,
    val banner: String? = null,
    val spotOffer: Pair<String, FavoriteSpot>? = null,
    val summary: WalkSummary? = null,
    val highlights: HighlightsCard? = null,
    val coupleMode: Boolean = false,
    val pingEnabled: Boolean = false,
    val showCalories: Boolean = false,
    val solo: Boolean = false,
)

/**
 * Owns one walk from lobby to summary. Lives in the app container so it survives screen changes;
 * [WalkService] keeps the process alive while it is not idle. Location and step data go only to the engine (on device)
 * and, as small messages, directly to connected buddies over WebRTC data channels.
 */
class WalkSession(
    private val app: Context,
    private val repo: AppRepository,
    private val settingsStore: SettingsStore,
    private val steps: StepRecorder,
    private val stepSource: StepSource,
    private val locationSource: LocationSource,
    private val health: HealthBridge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    val sessionActive: Boolean get() = _ui.value.phase == Phase.Lobby || _ui.value.phase == Phase.Walking

    private var settings = Settings()
    private var selfId = ""
    private var engine: WalkEngine? = null
    private var link: PeerLink? = null
    private var signaling: SignalingClient? = null
    private var code: String? = null
    private var serverUrl: String? = null
    private var walkStartMs = 0L
    private var reconnects = 0
    private val names = LinkedHashMap<String, String>()
    private val connected = HashSet<String>()
    private val walkJobs = mutableListOf<Job>()
    private var bannerJob: Job? = null
    private val pingOut = PingLimiter()
    private val pingIn = PingLimiter(minGapMs = 30_000, maxPerHour = 12)

    // ---------- lobby ----------

    /** [rawCodeOrLink] null = create a new session. Blank server and no code = solo walk. */
    fun openLobby(rawCodeOrLink: String?, serverOverride: String?, solo: Boolean = false) {
        if (sessionActive) return
        scope.launch {
            settings = settingsStore.current()
            selfId = settingsStore.ensurePeerId()
            names.clear(); connected.clear(); reconnects = 0
            val target = rawCodeOrLink?.let { JoinLink.parse(it) }
            code = if (solo) null else target?.code ?: SessionCode.generate()
            serverUrl = (serverOverride ?: target?.serverUrl)?.takeIf { it.isNotBlank() } ?: settings.serverUrl.takeIf { it.isNotBlank() }
            _ui.value = SessionUi(
                phase = Phase.Lobby, code = code,
                joinLink = code?.let { JoinLink.build(it, serverUrl) },
                quiet = settings.quietByDefault, pingEnabled = settings.pingEnabled, showCalories = settings.caloriesEnabled, solo = solo,
                note = if (!solo && serverUrl == null) "Add a signaling server in Settings to connect with a buddy. You can still walk solo." else null,
            )
            if (!solo && serverUrl != null) connectSignaling()
        }
    }

    private fun connectSignaling() {
        val url = serverUrl ?: return
        val c = code ?: return
        link?.close()
        link = PeerLink(
            context = app, selfId = selfId,
            sendSignal = { m -> signaling?.send(m) },
            onText = { from, text -> scope.launch { onPeerText(from, text) } },
            onPeerConnected = { id, ok -> scope.launch { onPeerConnected(id, ok) } },
        )
        signaling = SignalingClient(
            handleMessage = { m -> scope.launch { onSignal(m) } },
            handleState = { s, msg -> scope.launch { onSignalingState(s, msg, c) } },
        ).also { it.connect(url) }
    }

    private fun onSignalingState(s: SignalingState, msg: String?, c: String) {
        if (!sessionActive) return
        _ui.update { it.copy(signaling = s, note = if (s == SignalingState.Failed) (msg ?: "Connection problem") else if (s == SignalingState.Connected) null else it.note) }
        when (s) {
            SignalingState.Connected -> {
                reconnects = 0
                signaling?.send(SignalingMessage.Join(c, selfId))
            }
            SignalingState.Failed -> if (reconnects < 6) {
                reconnects++
                scope.launch {
                    delay(2_000L * reconnects)
                    if (sessionActive && _ui.value.signaling == SignalingState.Failed) signaling?.let { sc -> serverUrl?.let { sc.connect(it) } }
                }
            }
            else -> Unit
        }
    }

    private fun onSignal(m: SignalingMessage) {
        when (m) {
            is SignalingMessage.Joined -> m.peers.forEach { link?.callPeer(it) }
            is SignalingMessage.PeerJoined -> Unit // they will send us an offer
            is SignalingMessage.PeerLeft -> { link?.peerLeft(m.peerId); connected.remove(m.peerId); refreshPeers() }
            is SignalingMessage.Relay -> link?.onRelay(m)
            is SignalingMessage.Error -> _ui.update { it.copy(note = errorText(m.code)) }
            is SignalingMessage.Join -> Unit
        }
    }

    private fun errorText(code: String) = when (code) {
        "room_full" -> "That walk already has four people."
        "id_taken" -> "This phone is already in that walk."
        "room_expired" -> "That walk session timed out. Start a new one."
        "too_many_joins" -> "Too many tries. Wait a minute and try again."
        else -> "Could not join (${code})."
    }

    private fun onPeerConnected(id: String, ok: Boolean) {
        if (ok) {
            connected.add(id)
            link?.broadcast(MessageCodec.encode(PeerMessage.Hello(selfId, settings.displayName.ifBlank { "Buddy" })))
        } else {
            connected.remove(id)
        }
        refreshPeers()
    }

    private fun onPeerText(from: String, text: String) {
        val r = MessageCodec.decode(text) as? DecodeResult.Ok ?: return
        val msg = r.message
        if (msg is PeerMessage.Hello) names[from] = msg.name
        val now = System.currentTimeMillis()
        if (msg is PeerMessage.Ping) {
            // Works in the lobby and during the walk; rate-limited on the receiving side too.
            if (pingIn.tryAcquire(now)) {
                Notifications.haptic(app, strong = true)
                showBanner("${names[from] ?: "Your buddy"} is thinking of you", nudge = false)
            }
            return
        }
        if (msg is PeerMessage.Spot && engine == null) {
            _ui.update { it.copy(spotOffer = (names[from] ?: "Your buddy") to FavoriteSpot(msg.name, msg.lat, msg.lon)) }
            return
        }
        engine?.onPeer(now, from, msg)
        if (msg is PeerMessage.Bye) connected.remove(from)
        refreshPeers()
    }

    private fun refreshPeers() {
        val ids = (names.keys + connected).toSet()
        val peers = ids.map { LobbyPeer(it, names[it] ?: "Buddy", it in connected) }
        _ui.update { it.copy(peers = peers, coupleMode = CoupleMode.applies(peers.count { p -> p.connected } + 1)) }
    }

    // ---------- walking ----------

    fun startWalking() {
        if (_ui.value.phase != Phase.Lobby) return
        scope.launch {
            val s = settingsStore.current()
            settings = s
            val now = System.currentTimeMillis()
            walkStartMs = now
            steps.recordIdle(now) // account for steps before the walk as ordinary daily steps
            val stepLen = s.calibratedStepLengthM
            val cfg = WalkConfig(
                radiusM = s.radiusM.toDouble(),
                nudge = NudgeConfig(quiet = _ui.value.quiet, paceSync = s.paceSync),
                profile = s.profile, caloriesEnabled = s.caloriesEnabled, stepLengthOverrideM = stepLen,
            )
            val e = WalkEngine(selfId, s.displayName.ifBlank { "Me" }, cfg, now)
            for ((id, n) in names) e.onPeer(now, id, PeerMessage.Hello(id, n))
            engine = e
            _ui.update { it.copy(phase = Phase.Walking) }

            walkJobs += scope.launch { locationSource.fixes().collect { e.onSelfFix(it) } }
            walkJobs += scope.launch {
                stepSource.readings().collect { counter ->
                    val t = System.currentTimeMillis()
                    val d = e.onSelfSteps(t, counter)
                    steps.recordWalkDelta(t, counter, d)
                }
            }
            walkJobs += scope.launch { tickLoop(e) }
        }
    }

    private suspend fun tickLoop(e: WalkEngine) {
        var n = 0
        while (true) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val st = e.tick(now)
            n++
            if (n % 2 == 0) e.selfPosition(now)?.let { link?.broadcast(MessageCodec.encode(it)) }
            if (n % 30 == 0) steps.persistBaseline()
            _ui.update { it.copy(walk = st) }
            st.nudge?.let { showBanner(it.text, nudge = true) }
            e.takeSpot()?.let { offer -> _ui.update { it.copy(spotOffer = offer) } }
        }
    }

    private fun showBanner(text: String, nudge: Boolean) {
        Notifications.haptic(app)
        if (nudge) Notifications.nudge(app, text)
        _ui.update { it.copy(banner = text) }
        bannerJob?.cancel()
        bannerJob = scope.launch { delay(12_000); _ui.update { it.copy(banner = null) } }
    }

    fun dismissBanner() = _ui.update { it.copy(banner = null) }

    /** Quiet mode mutes nudges for the whole walk; also usable in the lobby. */
    fun setQuiet(on: Boolean) {
        _ui.update { it.copy(quiet = on) }
        val e = engine ?: return
        e.updateConfig(e.config.copy(nudge = e.config.nudge.copy(quiet = on)))
    }

    /** Opt-in "thinking of you" ping. Rate-limited in the engine. */
    fun sendPing(): Boolean {
        if (!settings.pingEnabled || !sessionActive) return false
        val now = System.currentTimeMillis()
        if (!pingOut.tryAcquire(now)) return false
        return (link?.broadcast(MessageCodec.encode(PeerMessage.Ping(now))) ?: 0) > 0
    }

    fun shareSpot(s: FavoriteSpot) {
        link?.broadcast(MessageCodec.encode(PeerMessage.Spot(s.name, s.lat, s.lon)))
    }

    fun acceptSpotOffer(save: Boolean) {
        val offer = _ui.value.spotOffer ?: return
        _ui.update { it.copy(spotOffer = null) }
        if (save) scope.launch { repo.addSpot(offer.second) }
    }

    fun endWalk() {
        val e = engine
        if (_ui.value.phase != Phase.Walking || e == null) { leave(); return }
        scope.launch {
            val now = System.currentTimeMillis()
            link?.broadcast(MessageCodec.encode(PeerMessage.Bye))
            walkJobs.forEach { it.cancel() }; walkJobs.clear()
            val summary = e.finish(now)
            steps.persistBaseline()
            teardownNetwork() // sharing ends when the walk ends
            repo.saveWalk(summary, walkStartMs)
            if (settings.healthConnectOn) health.writeWalk(walkStartMs, now, summary.verifiedSteps, summary.distanceM)
            val card = Highlights.build(summary, showCalories = settings.caloriesEnabled)
            engine = null
            _ui.update { it.copy(phase = Phase.Summary, summary = summary, highlights = card, walk = null, signaling = SignalingState.Idle, banner = null, peers = emptyList()) }
        }
    }

    /** Leave the lobby (or abandon a walk) without saving. */
    fun leave() {
        walkJobs.forEach { it.cancel() }; walkJobs.clear()
        link?.broadcast(MessageCodec.encode(PeerMessage.Bye))
        teardownNetwork()
        engine = null
        _ui.value = SessionUi()
    }

    fun finishSummary() {
        _ui.value = SessionUi()
    }

    private fun teardownNetwork() {
        signaling?.close(); signaling = null
        link?.close(); link = null
        names.clear(); connected.clear()
    }
}
