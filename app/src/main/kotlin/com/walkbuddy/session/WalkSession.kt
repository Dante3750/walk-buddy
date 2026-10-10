package com.walkbuddy.session

import android.content.Context
import com.walkbuddy.data.AppRepository
import com.walkbuddy.data.RouteStore
import com.walkbuddy.data.Settings
import com.walkbuddy.data.SettingsStore
import com.walkbuddy.data.StepRecorder
import com.walkbuddy.health.HealthBridge
import com.walkbuddy.notify.Notifications
import com.walkbuddy.domain.ServerConfig
import com.walkbuddy.rtc.SignalingClient
import com.walkbuddy.rtc.SignalingState
import com.walkbuddy.sensors.LocationSource
import com.walkbuddy.data.Clock
import com.walkbuddy.data.Goals
import com.walkbuddy.domain.ActivityState
import com.walkbuddy.domain.Avatar
import com.walkbuddy.domain.AvatarCode
import com.walkbuddy.domain.LinkMode
import com.walkbuddy.domain.LocationStatus
import com.walkbuddy.domain.LocationStatusLogic
import com.walkbuddy.domain.Polyline
import com.walkbuddy.domain.SharedMode
import com.walkbuddy.domain.SharedWalkRecorder
import com.walkbuddy.domain.TrackBuilders
import com.walkbuddy.domain.BuddyCard
import com.walkbuddy.domain.BuddyStatus
import com.walkbuddy.domain.CoupleMode
import com.walkbuddy.domain.PaceZone
import com.walkbuddy.domain.Reaction
import com.walkbuddy.domain.Reactions
import com.walkbuddy.domain.RingBuddy
import com.walkbuddy.domain.TogetherSnapshot
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.Highlights
import com.walkbuddy.domain.HighlightsCard
import com.walkbuddy.domain.JoinLink
import com.walkbuddy.domain.LatLon
import com.walkbuddy.domain.MeetingPin
import com.walkbuddy.domain.RouteRecorder
import com.walkbuddy.domain.TrailBook
import com.walkbuddy.domain.MotionGate
import com.walkbuddy.domain.SendGate
import com.walkbuddy.power.PowerMonitor
import com.walkbuddy.rtc.NetworkWatcher
import com.walkbuddy.domain.NudgeConfig
import com.walkbuddy.domain.PeerMessage
import com.walkbuddy.domain.PaceCoach
import com.walkbuddy.domain.CoachAdvice
import com.walkbuddy.domain.CoachConfig
import com.walkbuddy.domain.CoachInput
import com.walkbuddy.domain.CoachMode
import com.walkbuddy.R
import com.walkbuddy.domain.ResumeCodec
import com.walkbuddy.domain.ResumeKind
import com.walkbuddy.domain.ResumePolicy
import com.walkbuddy.domain.ResumeState
import com.walkbuddy.diag.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

private const val MIN_SHARED_WALK_MS = 60_000L

enum class Phase { Idle, Lobby, Walking, Summary }

data class LobbyPeer(val id: String, val name: String, val connected: Boolean)

/** A warm preset reaction that just arrived from a buddy. [seq] makes repeated identical reactions distinct. */
data class IncomingReaction(val from: String, val reaction: Reaction, val seq: Int)

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
    val walkId: Long? = null,
    /** Buddies at their own daily progress, from their "day" messages (for the dots on the live ring). */
    val buddyDaily: List<RingBuddy> = emptyList(),
    val reaction: IncomingReaction? = null,
    val demo: Boolean = false,
    /** Recent paths for the map, by person id (mine is keyed "me"). */
    val trails: Map<String, List<LatLon>> = emptyMap(),
    /** A meeting point either partner has set on the map. */
    val pin: MeetingPin? = null,
    /** How the link to my partner is doing right now: direct, via the server, reconnecting. */
    val link: LinkMode = LinkMode.Waiting,
    /** Why my own position may be missing (permission, switched-off location, still searching), with how long I have been searching. */
    val locStatus: LocationStatus = LocationStatus.Ok,
    val locSearchingSec: Long = 0,
    /** My look on the Track. */
    val myAvatar: Avatar = Avatar.Default,
    val myId: String = "",
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
    private val locationSource: LocationSource,
    private val health: HealthBridge,
    private val routes: RouteStore,
    private val power: PowerMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    val sessionActive: Boolean get() = _ui.value.phase == Phase.Lobby || _ui.value.phase == Phase.Walking

    private var settings = Settings()
    private var selfId = ""
    private var engine: WalkEngine? = null
    private var transport: PartnerTransport? = null
    private var linkJob: Job? = null
    private var locJob: Job? = null
    private var recorder: SharedWalkRecorder? = null
    private val helloTo = HashSet<String>()
    private val gone = HashSet<String>()
    private var searchingSinceMs = 0L
    private var hadLocationPermission = false
    private var signaling: SignalingClient? = null
    private var code: String? = null
    private var walkStartMs = 0L
    private val net = NetworkWatcher(app)
    private val reconnector = Reconnector(scope, net, maxAttempts = 40, stillNeeded = { sessionActive }, reconnect = { if (_ui.value.signaling == SignalingState.Failed) signaling?.connect(ServerConfig.URL) })
    private var netJob: Job? = null
    private val sendGate = SendGate()
    private val motion = MotionGate()
    private val names = LinkedHashMap<String, String>()
    private val avatars = HashMap<String, Int>()
    private val connected = HashSet<String>()
    private val walkJobs = mutableListOf<Job>()
    private var bannerJob: Job? = null
    private val pingOut = PingLimiter()
    private val pingIn = PingLimiter(minGapMs = 30_000, maxPerHour = 12)
    private val reactionOut = Reactions.senderLimiter()
    private val reactionIn = Reactions.receiverLimiter()
    private val peerDaily = HashMap<String, Pair<Int, Int>>()
    private var reactionSeq = 0
    private var reactionJob: Job? = null
    private var lastBuddySaveMs = 0L
    private val trailBook = TrailBook()
    private var route: RouteRecorder? = null
    /** The walk that was running when Android killed the app, while it is being resumed (alpha 2.0). */
    private var carry: ResumeState? = null
    private val coach = PaceCoach()
    private var lastResumeSaveMs = 0L
    private var lastResumeDistM = 0.0

    // ---------- lobby ----------

    /** [rawCodeOrLink] null = create a new session. */
    fun openLobby(rawCodeOrLink: String?, solo: Boolean = false) {
        if (sessionActive) return
        // Mark the session active right away (before the settings are read) so the foreground service never sees "idle" first.
        _ui.value = SessionUi(phase = Phase.Lobby, solo = solo)
        scope.launch {
            settings = settingsStore.current()
            selfId = settingsStore.ensurePeerId()
            names.clear(); connected.clear(); helloTo.clear(); gone.clear(); reconnector.cancel()
            val target = rawCodeOrLink?.let { JoinLink.parse(it) }
            code = if (solo) null else target?.code ?: SessionCode.generate()
            _ui.value = SessionUi(
                phase = Phase.Lobby, code = code,
                joinLink = code?.let { JoinLink.build(it) },
                quiet = settings.quietByDefault, pingEnabled = settings.pingEnabled, showCalories = settings.caloriesEnabled, solo = solo,
                myAvatar = settings.avatar, myId = selfId,
            )
            if (!solo) {
                net.start()
                netJob?.cancel()
                val t0 = System.currentTimeMillis()
                netJob = scope.launch {
                    launch { net.online.collect { on -> transport?.online = on; if (on) reconnector.onNetworkBack(); refreshLink() } }
                    launch { net.changes.collect { onNetworkChanged() } }
                }
                connectSignaling()
                startLinkLoop(t0)
            }
        }
    }

    private fun connectSignaling() {
        val url = ServerConfig.URL
        val c = code ?: return
        transport?.close()
        transport = PartnerTransport(
            context = app, selfId = selfId, scope = scope,
            signal = { m -> signaling?.send(m) ?: false },
            onMessage = { from, msg -> onPeerMessage(from, msg) },
            onChange = { onTransportChange() },
            resumeKey = carry?.takeIf { it.kind == ResumeKind.Partner }?.key,
        ).also { it.online = net.online.value }
        signaling = SignalingClient(
            handleMessage = { m -> scope.launch { onSignal(m) } },
            handleState = { s, msg -> scope.launch { onSignalingState(s, msg, c) } },
        ).also { it.connect(url) }
    }

    /** Wi-Fi to mobile data and back: the server socket and the direct path both just lost their route. */
    private fun onNetworkChanged() {
        val t = transport ?: return
        val now = System.currentTimeMillis()
        t.onNetworkChanged(now)
        // Replace the server socket at once instead of waiting for it to time out; the session key lets us take our place back.
        if (_ui.value.signaling == SignalingState.Connected) { reconnector.cancel(); signaling?.connect(ServerConfig.URL) }
    }

    /** Every couple of seconds while a session is open: repair the direct path and keep the link label honest. */
    private fun startLinkLoop(startMs: Long) {
        linkJob?.cancel()
        linkJob = scope.launch {
            while (true) {
                delay(2_000)
                val now = System.currentTimeMillis()
                transport?.poll(now)
                refreshLink()
            }
        }
    }

    private fun refreshLink() {
        val t = transport ?: return
        val m = if (_ui.value.solo) LinkMode.Waiting else t.mode(System.currentTimeMillis())
        if (m != _ui.value.link) _ui.update { it.copy(link = m) }
    }

    private fun onSignalingState(s: SignalingState, msg: String?, c: String) {
        if (!sessionActive) return
        _ui.update {
            it.copy(
                signaling = s,
                note = when (s) {
                    SignalingState.Failed -> if (!reconnector.exhausted) ServerConfig.RETRY_NOTE else (msg ?: "Connection problem")
                    SignalingState.Connecting -> ServerConfig.WAKING_NOTE
                    SignalingState.Connected -> null
                    else -> it.note
                },
            )
        }
        val now = System.currentTimeMillis()
        when (s) {
            SignalingState.Connected -> {
                reconnector.onConnected()
                signaling?.send(SignalingMessage.Join(c, selfId, transport?.sessionKey))
                transport?.onServer(true, now)
            }
            SignalingState.Failed -> { transport?.onServer(false, now); reconnector.onFailed() }
            SignalingState.Connecting, SignalingState.Idle -> transport?.onServer(false, now)
        }
        refreshLink()
    }

    private fun onSignal(m: SignalingMessage) {
        val now = System.currentTimeMillis()
        when (m) {
            is SignalingMessage.Joined -> transport?.onJoined(m.peers, now)
            is SignalingMessage.PeerJoined -> { gone.remove(m.peerId); transport?.onPeerJoined(m.peerId, now) } // they place the call
            is SignalingMessage.PeerLeft -> transport?.onPeerLeft(m.peerId, now)
            is SignalingMessage.Relay -> transport?.onSignal(m)
            is SignalingMessage.PairData -> transport?.onRelayData(m.from, m.data)
            is SignalingMessage.Error -> errorText(m.code)?.let { n -> _ui.update { it.copy(note = n) } }
            is SignalingMessage.Join -> Unit
            is SignalingMessage.PairSend -> Unit
        }
    }

    private fun errorText(code: String) = when (code) {
        "room_full" -> "That walk already has four people."
        "id_taken" -> "This phone is already in that walk."
        "room_expired" -> "That walk session timed out. Start a new one."
        "too_many_joins" -> "Too many tries. Wait a minute and try again."
        "bad_message" -> null
        else -> "Could not join (${code})."
    }

    /** Who is reachable (directly or through the server) may have changed: refresh the list and greet anyone new. */
    private fun onTransportChange() {
        val t = transport ?: return
        val now = System.currentTimeMillis()
        val reachable = (names.keys + t.roomPeers).filter { t.reachable(it) && it !in gone }.toSet()
        helloTo.retainAll(reachable)
        for (id in reachable) if (helloTo.add(id)) {
            sendPeer(PeerMessage.Hello(selfId, settings.displayName.ifBlank { "Buddy" }, AvatarCode.encode(settings.avatar)), now)
            _ui.value.pin?.let { sendPeer(PeerMessage.Pin(it.pos.lat, it.pos.lon, it.label), now) }
        }
        refreshPeers()
        refreshLink()
    }

    /** Sends to my buddies by the best path(s). Returns how many routes took it. */
    private fun sendPeer(m: PeerMessage, now: Long = System.currentTimeMillis()): Int = transport?.send(m, now) ?: 0

    private fun onPeerMessage(from: String, msg: PeerMessage) {
        if (msg is PeerMessage.Hello) { names[from] = msg.name; msg.avatar?.let { avatars[from] = it } }
        val now = System.currentTimeMillis()
        gone.remove(from)
        if (msg is PeerMessage.Ping) {
            // Works in the lobby and during the walk; rate-limited on the receiving side too.
            if (pingIn.tryAcquire(now)) {
                Notifications.haptic(app, strong = true)
                showBanner("${names[from] ?: "Your buddy"} is thinking of you", nudge = false)
            }
            return
        }
        if (msg is PeerMessage.React) {
            val r = Reaction.fromId(msg.id) ?: return
            if (reactionIn.tryAcquire(now)) showReaction(names[from] ?: "Your buddy", r)
            return
        }
        if (msg is PeerMessage.Daily) {
            peerDaily[from] = msg.steps to msg.goal
            refreshDaily()
            if (now - lastBuddySaveMs > 30_000) {
                lastBuddySaveMs = now
                scope.launch { settingsStore.saveBuddyDaily(names[from] ?: "Buddy", msg.steps, msg.goal, Clock.today()) }
            }
            return
        }
        if (msg is PeerMessage.Pin) {
            _ui.update { it.copy(pin = MeetingPin(LatLon(msg.lat, msg.lon), msg.label)) }
            showBanner("${names[from] ?: "Your buddy"} set a meeting point on the map", nudge = false)
            return
        }
        if (msg is PeerMessage.Unpin) { _ui.update { it.copy(pin = null) }; return }
        if (msg is PeerMessage.Spot && engine == null) {
            _ui.update { it.copy(spotOffer = (names[from] ?: "Your buddy") to FavoriteSpot(msg.name, msg.lat, msg.lon)) }
            return
        }
        engine?.onPeer(now, from, msg)
        if (msg is PeerMessage.Bye) { gone.add(from); transport?.forget(from) }
        else onTransportChange()
    }

    private fun refreshDaily() {
        val list = peerDaily.map { (id, v) -> RingBuddy(id, names[id] ?: "Buddy", v.first, v.second) }
        _ui.update { it.copy(buddyDaily = list) }
    }

    private fun showReaction(from: String, r: Reaction) {
        Notifications.haptic(app)
        _ui.update { it.copy(reaction = IncomingReaction(from, r, ++reactionSeq)) }
        reactionJob?.cancel()
        reactionJob = scope.launch { delay(4_000); _ui.update { it.copy(reaction = null) } }
    }

    /** Sends one of the preset reactions. Rate limited; returns false when it was not sent. */
    fun sendReaction(r: Reaction): Boolean {
        if (_ui.value.phase != Phase.Walking) return false
        if (_ui.value.demo) { showReaction("You", r); return true }
        if (!reactionOut.tryAcquire(System.currentTimeMillis())) return false
        return sendPeer(PeerMessage.React(r.id)) > 0
    }

    fun dismissReaction() = _ui.update { it.copy(reaction = null) }

    private fun refreshPeers() {
        val t = transport
        connected.clear()
        if (t != null) connected.addAll((names.keys + t.roomPeers).filter { t.reachable(it) && it !in gone })
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
            // A resumed walk keeps its place in time: the minutes the app was gone are not counted as walking.
            walkStartMs = carry?.let { now - it.walkedMs } ?: now
            lastResumeSaveMs = 0L; lastResumeDistM = 0.0
            coach.config = CoachConfig(mode = CoachMode.fromName(s.coachMode)); coach.reset()
            val startSample = steps.sampleNow() // credits steps before the walk to the day, and gives the walk its starting counter
            val stepLen = s.calibratedStepLengthM
            val cfg = WalkConfig(
                radiusM = s.radiusM.toDouble(),
                nudge = NudgeConfig(quiet = _ui.value.quiet, paceSync = s.paceSync),
                profile = s.profile, caloriesEnabled = s.caloriesEnabled, stepLengthOverrideM = stepLen,
            )
            val e = WalkEngine(selfId, s.displayName.ifBlank { "Me" }, cfg, now)
            for ((id, n) in names) e.onPeer(now, id, PeerMessage.Hello(id, n, avatars[id]))
            engine = e
            _ui.update { it.copy(phase = Phase.Walking) }

            trailBook.clear()
            route = if (s.saveRoutes) RouteRecorder() else null
            carry?.let { c0 ->
                route?.let { r -> Polyline.decode(c0.route).forEach { pt -> r.add(c0.startMs, pt) } }
                if (c0.pinLat != null && c0.pinLon != null) _ui.update { it.copy(pin = MeetingPin(LatLon(c0.pinLat!!, c0.pinLon!!), c0.pinLabel.orEmpty())) }
                AppLog.i("resume", "walk resumed (${c0.kind.name})")
            }
            sendGate.reset(); motion.reset()
            power.setWalk(true, groupSize = connected.size + 1)
            recorder = if (_ui.value.solo || _ui.value.demo) null else SharedWalkRecorder(now, SharedMode.Partner)
            searchingSinceMs = now
            hadLocationPermission = locationSource.hasPermission()
            startLocation(e)
            walkJobs += scope.launch {
                // The walk reads the same feed as the all-day counter; it never writes daily steps itself, so nothing is counted twice.
                steps.acquire("walk")
                try {
                    startSample?.let { e.onSelfSteps(it.baseline.tMs, it.baseline.counter) }
                    val from = startSample?.baseline?.tMs ?: now
                    steps.live.collect { r -> if (r.wallMs >= from) e.onSelfSteps(r.wallMs, r.counter) }
                } finally {
                    steps.release("walk")
                }
            }
            walkJobs += scope.launch { tickLoop(e) }
        }
    }

    /** GPS only for the length of this walk, at the rate the power policy asks for. Can be called again after a permission was granted. */
    private fun startLocation(e: WalkEngine) {
        locJob?.cancel()
        locJob = scope.launch {
            locationSource.fixes(power.locationPlans()).collect {
                e.onSelfFix(it)
                route?.add(System.currentTimeMillis(), it.pos, it.accuracyM)
            }
        }
        locJob?.let { walkJobs += it }
    }

    /** The person just allowed location (or switched it on) while the walk runs: start listening again. */
    fun onLocationAvailable() {
        val e = engine ?: return
        if (_ui.value.phase == Phase.Walking && !_ui.value.demo) startLocation(e)
    }

    private fun locationStatus(st: WalkState, now: Long): Pair<LocationStatus, Long> {
        val perm = locationSource.hasPermission()
        if (perm && !hadLocationPermission) engine?.let { startLocation(it) } // granted in the settings app or the dialog
        hadLocationPermission = perm
        val status = LocationStatusLogic.of(perm, locationSource.anyProviderOn(), st.locationQuality, locationSource.hasPrecisePermission())
        if (status != LocationStatus.Searching) searchingSinceMs = now
        return status to ((now - searchingSinceMs) / 1000L).coerceAtLeast(0)
    }

    private suspend fun tickLoop(e: WalkEngine) {
        var lastDailyMs = 0L
        while (true) {
            // Every second with the screen on, every few seconds in a pocket (the engine caps its own time step at 10 s).
            delay(power.plan().tickMs)
            val now = System.currentTimeMillis()
            val st = e.tick(now)
            power.setMoving(motion.update(now, st.mySpeedMps))
            power.setGroupSize(connected.size + 1)
            val plan = power.plan()
            if (sendGate.poll(now, plan.sendIntervalMs)) e.selfPosition(now)?.let { sendPeer(it, now) }
            // A quiet heartbeat when there is no position to send (no GPS yet), so the buddy's screen shows a live link, not a silent one.
            else if (transport?.msSinceSend(now)?.let { it > 20_000L } == true) sendPeer(PeerMessage.Hello(selfId, settings.displayName.ifBlank { "Buddy" }, AvatarCode.encode(settings.avatar)), now)
            if (now - lastDailyMs >= plan.dailyBroadcastMs) { lastDailyMs = now; broadcastDaily() }
            st.myPos?.let { trailBook.add("me", it) }
            st.buddies.forEach { b -> b.pos?.let { trailBook.add(b.id, it) } }
            val (locSt, locSec) = locationStatus(st, now)
            recordShared(st, now)
            _ui.update { it.copy(walk = st, trails = trailBook.snapshot(), locStatus = locSt, locSearchingSec = locSec) }
            saveResume(st, now)
            coachTick(st, now)
            refreshLink()
            st.nudge?.let { showBanner(it.text, nudge = true) }
            e.takeSpot()?.let { offer -> _ui.update { it.copy(spotOffer = offer) } }
        }
    }

    /** Saves a small snapshot every ~20 s or 60 m so a walk survives the app being killed. It stays in the app's own settings. */
    private fun saveResume(st: WalkState, now: Long) {
        if (_ui.value.demo) return
        if (!ResumePolicy.shouldSave(lastResumeSaveMs, lastResumeDistM, now, st.myDistanceM)) return
        lastResumeSaveMs = now; lastResumeDistM = st.myDistanceM
        val c0 = carry
        val pts = route?.points.orEmpty().map { LatLon(it.lat, it.lon) }
        val pin = _ui.value.pin
        val state = ResumeState(
            kind = if (_ui.value.solo) ResumeKind.Solo else ResumeKind.Partner, startMs = walkStartMs, savedMs = now,
            code = code, key = transport?.sessionKey,
            distanceM = st.myDistanceM + (c0?.distanceM ?: 0.0), verifiedSteps = st.myVerifiedSteps + (c0?.verifiedSteps ?: 0L), rawSteps = st.myRawSteps + (c0?.rawSteps ?: 0L),
            route = ResumePolicy.compactRoute(pts), pinLat = pin?.pos?.lat, pinLon = pin?.pos?.lon, pinLabel = pin?.label,
        )
        val json = ResumeCodec.encode(state)
        scope.launch { settingsStore.setResume(json) }
    }

    /** Optional gentle pace hints (off by default). Respects quiet mode and quiet hours, and has its own long cooldown. */
    private fun coachTick(st: WalkState, now: Long) {
        if (coach.config.mode == CoachMode.Off || st.buddies.isEmpty()) return
        val advice = coach.update(
            CoachInput(
                nowMs = now, mySpeedMps = st.mySpeedMps, othersMps = st.buddies.map { if (it.pos != null) it.speedMps else null },
                iAmSweeper = false, quiet = _ui.value.quiet, quietHours = settings.quietHours.isQuiet(Clock.hourOfDay(now)),
            ),
        )
        when (advice) {
            is CoachAdvice.EaseOff -> showBanner(app.getString(if (coach.config.mode == CoachMode.SlowestPace) R.string.coach_slowest else R.string.coach_ease), nudge = true)
            is CoachAdvice.PickUp -> showBanner(app.getString(R.string.coach_pickup), nudge = true)
            CoachAdvice.None -> Unit
        }
    }

    private fun clearResume() {
        carry = null
        scope.launch { settingsStore.setResume("") }
    }

    /** Picks up a walk that Android ended: same code and key, so the partner sees the same person come back. */
    fun resumeWalk(state: ResumeState) {
        if (sessionActive) return
        carry = state
        openLobby(if (state.kind == ResumeKind.Partner) state.code else null, solo = state.kind == ResumeKind.Solo)
        scope.launch {
            withTimeoutOrNull(5_000) { _ui.first { it.myId.isNotEmpty() } }
            if (_ui.value.phase == Phase.Lobby) startWalking() else carry = null
        }
    }

    private fun recordShared(st: WalkState, now: Long) {
        val rec = recorder ?: return
        if (st.buddies.isEmpty()) return
        val walkers = TrackBuilders.forPartner(st, selfId, settings.avatar)
        val lanes = TrackBuilders.recLanes(walkers, { w -> if (w.isMe) AvatarCode.encode(settings.avatar) else AvatarCode.encode(w.avatar) }, { w -> if (w.isMe) st.myDistanceM else null })
        rec.onFrame(now, lanes, TrackBuilders.partnerGap(st))
    }

    private suspend fun broadcastDaily() {
        if (transport == null) return
        val day = Clock.today()
        val history = repo.allDays()
        val steps = history.firstOrNull { it.epochDay == day }?.verifiedSteps ?: 0
        val goal = Goals.goalFor(day, history, settings)
        sendPeer(PeerMessage.Daily(steps.coerceIn(0, 300_000), goal.coerceIn(500, 100_000)))
    }

    private fun showBanner(text: String, nudge: Boolean) {
        Notifications.haptic(app)
        if (nudge && !settings.quietHours.isQuiet(Clock.hourOfDay())) Notifications.nudge(app, text)
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
        return sendPeer(PeerMessage.Ping(now), now) > 0
    }

    /** Sets (and tells my partner about) a meeting point on the map. */
    fun setPin(pos: LatLon, label: String = "") {
        if (_ui.value.phase != Phase.Walking && _ui.value.phase != Phase.Lobby) return
        val pin = MeetingPin(pos, label.trim().take(40))
        _ui.update { it.copy(pin = pin) }
        sendPeer(PeerMessage.Pin(pos.lat, pos.lon, pin.label))
    }

    fun clearPin() {
        _ui.update { it.copy(pin = null) }
        sendPeer(PeerMessage.Unpin)
    }

    fun shareSpot(s: FavoriteSpot) {
        sendPeer(PeerMessage.Spot(s.name, s.lat, s.lon))
    }

    fun acceptSpotOffer(save: Boolean) {
        val offer = _ui.value.spotOffer ?: return
        _ui.update { it.copy(spotOffer = null) }
        if (save) scope.launch { repo.addSpot(offer.second) }
    }

    fun endWalk() {
        if (_ui.value.demo && _ui.value.phase == Phase.Walking) { endDemoWalk(); return }
        val e = engine
        if (_ui.value.phase != Phase.Walking || e == null) { leave(); return }
        scope.launch {
            val now = System.currentTimeMillis()
            sendPeer(PeerMessage.Bye, now)
            walkJobs.forEach { it.cancel() }; walkJobs.clear()
            val summary = e.finish(now).let { s0 -> carry?.let { ResumePolicy.carryInto(s0, it) } ?: s0 }
            clearResume()
            teardownNetwork() // sharing ends when the walk ends
            val walkId = repo.saveWalk(summary, walkStartMs)
            com.walkbuddy.health.HealthSync.writeWalkIfNew(health, settingsStore, settings, walkStartMs, now, summary.verifiedSteps, summary.distanceM)
            val card = Highlights.build(summary, showCalories = settings.caloriesEnabled)
            route?.let { r -> if (settings.saveRoutes) routes.save(walkStartMs, r.points) }
            saveSharedWalk(summary, now)
            route = null; recorder = null; trailBook.clear()
            engine = null
            _ui.update { it.copy(phase = Phase.Summary, summary = summary, highlights = card, walk = null, signaling = SignalingState.Idle, banner = null, peers = emptyList(), walkId = walkId, buddyDaily = emptyList(), reaction = null) }
        }
    }

    /** Keeps the replay of a walk that really was shared, for the "Walks together" history. A failure here must never lose the summary. */
    private suspend fun saveSharedWalk(summary: WalkSummary, endMs: Long) {
        val rec = recorder ?: return
        if (rec.laneCount < 2 || endMs - walkStartMs < MIN_SHARED_WALK_MS) return
        runCatching {
            val last = _ui.value.walk
            val lastFrame = last?.let { st ->
                val ws = TrackBuilders.forPartner(st, selfId, settings.avatar)
                TrackBuilders.recLanes(ws, { w -> AvatarCode.encode(w.avatar) }, { w -> if (w.isMe) summary.distanceM else null })
            }
            val points = route?.points.orEmpty().map { LatLon(it.lat, it.lon) }
            val poly = if (settings.saveRoutes && points.size >= 2) Polyline.encode(Polyline.simplify(points)) else null
            val title = names.values.firstOrNull { it.isNotBlank() } ?: "Buddy"
            val record = rec.build(
                endMs = endMs, title = title, memberCount = rec.laneCount, togetherPct = summary.togetherPct ?: 0,
                longestTogetherMs = summary.longestTogetherMs, route = poly, lastFrame = lastFrame,
            )
            repo.saveSharedWalk(record)
        }
    }

    /** Leave the lobby (or abandon a walk) without saving. */
    fun leave() {
        clearResume()
        walkJobs.forEach { it.cancel() }; walkJobs.clear()
        sendPeer(PeerMessage.Bye)
        teardownNetwork()
        engine = null
        route = null; recorder = null; trailBook.clear()
        _ui.value = SessionUi()
    }

    fun finishSummary() {
        _ui.value = SessionUi()
    }

    private fun teardownNetwork() {
        // Walk over: the policy stops location and fast sensors, the signaling socket and peer connections close, the network callback goes.
        power.setWalk(false)
        reconnector.cancel()
        netJob?.cancel(); netJob = null
        net.stop()
        linkJob?.cancel(); linkJob = null
        signaling?.close(); signaling = null
        transport?.close(); transport = null
        names.clear(); avatars.clear(); connected.clear(); peerDaily.clear(); helloTo.clear(); gone.clear()
    }

    // ---------- demo walk (no buddy, no permissions, nothing saved) ----------

    /** A scripted walk with a pretend buddy, so the live screen can be explored anywhere. */
    fun startDemoWalk() {
        if (_ui.value.phase != Phase.Idle) return
        val start = System.currentTimeMillis()
        walkStartMs = start
        trailBook.clear()
        _ui.value = SessionUi(phase = Phase.Walking, coupleMode = true, pingEnabled = true, demo = true, buddyDaily = listOf(RingBuddy("demo", "Sam", 6_120, 8_000)))
        scope.launch { settings = settingsStore.current(); _ui.update { it.copy(myAvatar = settings.avatar, myId = "me") } }
        val samAvatar = AvatarCode.encode(Avatar(com.walkbuddy.domain.AvatarStyle.Girl, 8))
        walkJobs += scope.launch {
            var k = 0
            while (true) {
                delay(1_000)
                k++
                val el = System.currentTimeMillis() - start
                val sec = el / 1000.0
                val steps = (sec * 1.8).toLong()
                // Now and then Sam drifts a little way off (ahead, then behind), so the Track's gap and the together glow can be seen.
                val gap = 6.0 + 34.0 * (0.5 + 0.5 * Math.sin(sec / 14.0))
                val along = gap * (if (Math.sin(sec / 40.0) > 0) 1.0 else -1.0)
                val buddy = BuddyCard(
                    id = "demo", name = "Sam", distanceM = gap, alongM = along,
                    relation = "Side by side", zone = PaceZone.Brisk, steps = (steps * 0.97).toInt(), speedMps = 1.28,
                    status = BuddyStatus.Moving, statusText = "Walking with you", pos = null, lastHeardAgoSec = 1, avatar = samAvatar,
                )
                val together = TogetherSnapshot(el, (el * 0.92).toLong(), 92, minOf(el, 240_000L), minOf(el, 240_000L))
                // A pretend route (north, then east) so the map has something to show.
                val demoMe = demoPoint(sec * 1.3, 0.0)
                val demoBuddy = demoPoint(sec * 1.3 + along, 8.0)
                trailBook.add("me", demoMe); trailBook.add("demo", demoBuddy)
                val st = WalkState(
                    nowMs = start + el, elapsedMs = el, myDistanceM = sec * 1.3, myVerifiedSteps = steps, myRawSteps = steps + 2,
                    myCadenceSpm = 108.0, myZone = PaceZone.Brisk, mySpeedMps = 1.3, myActivity = ActivityState.Walking,
                    buddies = listOf(buddy.copy(pos = demoBuddy)), together = together, togetherNow = true, nudge = null, paceSuggestion = null,
                    myPos = demoMe,
                )
                _ui.update { it.copy(trails = trailBook.snapshot(), walk = st, buddyDaily = listOf(RingBuddy("demo", "Sam", 6_120 + (steps * 0.97).toInt(), 8_000))) }
                if (k % 25 == 0) showReaction("Sam", Reaction.values()[(k / 25) % Reaction.values().size])
            }
        }
    }

    private fun demoPoint(s: Double, lateral: Double): LatLon {
        val o = LatLon(12.9716, 77.5946)
        val leg = 420.0
        fun at(p: LatLon, north: Double, east: Double) = LatLon(p.lat + north / 111_195.0, p.lon + east / (111_195.0 * Math.cos(Math.toRadians(p.lat))))
        return if (s <= leg) at(o, s, lateral) else at(at(o, leg, 0.0), -lateral, s - leg)
    }

    private fun endDemoWalk() {
        walkJobs.forEach { it.cancel() }; walkJobs.clear()
        val w = _ui.value.walk
        val el = w?.elapsedMs ?: 60_000L
        val summary = WalkSummary(
            durationMs = el, distanceM = w?.myDistanceM ?: 80.0, rawSteps = (w?.myRawSteps ?: 100), verifiedSteps = (w?.myVerifiedSteps ?: 100),
            avgSpeedMps = 1.3, buddyCount = 1, togetherPct = 92, longestTogetherMs = (el * 0.8).toLong(), moderateMin = (el / 60_000).toInt(),
            vigorousMin = 0, nudgesShown = 0, calories = null,
        )
        _ui.update { it.copy(phase = Phase.Summary, summary = summary, highlights = Highlights.build(summary, false), walk = null, walkId = null, reaction = null) }
    }
}
