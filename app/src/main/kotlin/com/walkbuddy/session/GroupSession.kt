package com.walkbuddy.session

import android.content.Context
import com.walkbuddy.data.AppRepository
import com.walkbuddy.data.Clock
import com.walkbuddy.data.RouteStore
import com.walkbuddy.data.Settings
import com.walkbuddy.data.SettingsStore
import com.walkbuddy.data.StepRecorder
import com.walkbuddy.domain.Avatar
import com.walkbuddy.domain.AvatarCode
import com.walkbuddy.domain.LocationStatus
import com.walkbuddy.domain.LocationStatusLogic
import com.walkbuddy.domain.Polyline
import com.walkbuddy.domain.SharedMode
import com.walkbuddy.domain.SharedWalkRecorder
import com.walkbuddy.domain.TrackBuilders
import com.walkbuddy.domain.GroupClientMessage
import com.walkbuddy.domain.GroupConfig
import com.walkbuddy.domain.GroupCopy
import com.walkbuddy.domain.ServerConfig
import com.walkbuddy.domain.GroupEngine
import com.walkbuddy.domain.GroupKey
import com.walkbuddy.domain.GroupLink
import com.walkbuddy.domain.GroupServerMessage
import com.walkbuddy.domain.GroupSettings
import com.walkbuddy.domain.GroupState
import com.walkbuddy.domain.GroupUpdate
import com.walkbuddy.domain.GroupWalkConfig
import com.walkbuddy.domain.Highlights
import com.walkbuddy.domain.HighlightsCard
import com.walkbuddy.domain.Invite
import com.walkbuddy.domain.Invites
import com.walkbuddy.domain.LatLon
import com.walkbuddy.domain.LocationPrecision
import com.walkbuddy.domain.MeetingPin
import com.walkbuddy.domain.NudgeConfig
import com.walkbuddy.domain.RosterEntry
import com.walkbuddy.domain.RouteRecorder
import com.walkbuddy.domain.SessionCode
import com.walkbuddy.domain.Fix
import com.walkbuddy.domain.WalkConfig
import com.walkbuddy.domain.WalkSummary
import com.walkbuddy.health.HealthBridge
import com.walkbuddy.notify.Notifications
import com.walkbuddy.rtc.GroupClient
import com.walkbuddy.rtc.NetworkWatcher
import com.walkbuddy.rtc.SignalingState
import com.walkbuddy.power.PowerMonitor
import com.walkbuddy.domain.MotionGate
import com.walkbuddy.domain.SendGate
import com.walkbuddy.sensors.LocationSource
import java.security.SecureRandom
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.asKotlinRandom
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

enum class GroupPhase { Idle, Active, Summary }

data class JoinRequestUi(val id: String, val name: String)

/** What the host chooses when creating a group. */
data class CreateGroupOptions(
    val nickname: String,
    val title: String = "",
    val approval: Boolean = false,
    val ttlMin: Int = 240,
    val goalSteps: Int = 0,
    val precision: LocationPrecision = LocationPrecision.Exact,
)

data class GroupUi(
    val phase: GroupPhase = GroupPhase.Idle,
    val demo: Boolean = false,
    val iAmHost: Boolean = false,
    val code: String? = null,
    val inviteLink: String? = null,
    val webLink: String? = null,
    val connection: SignalingState = SignalingState.Idle,
    /** True once the server has put me in the group (not while waiting for approval or connecting). */
    val joined: Boolean = false,
    val waitingForApproval: Boolean = false,
    val note: String? = null,
    val requests: List<JoinRequestUi> = emptyList(),
    val settings: GroupSettings = GroupSettings(),
    val expiresAtMs: Long? = null,
    val hostAway: Boolean = false,
    val walk: GroupState? = null,
    val roster: List<RosterEntry> = emptyList(),
    val pin: MeetingPin? = null,
    val sharing: Boolean = true,
    val precision: LocationPrecision = LocationPrecision.Exact,
    val quiet: Boolean = false,
    val iAmSweeper: Boolean = false,
    val banner: String? = null,
    val summary: WalkSummary? = null,
    val highlights: HighlightsCard? = null,
    val endedReason: String? = null,
    val groupSteps: Long = 0,
    val walkId: Long? = null,
    val locStatus: LocationStatus = LocationStatus.Ok,
    val locSearchingSec: Long = 0,
    val myAvatar: Avatar = Avatar.Default,
    val myId: String = "",
)

/**
 * An open group walk (many people). Unlike [WalkSession] (two private phones, WebRTC), the group talks to the server, which
 * relays small updates to everyone; see server/README.md. Tracking starts as soon as the server lets me in, so a late joiner
 * is simply a member whose first update arrives mid-walk. Location goes only to the group (blurred if the user chose so) and
 * ends when the walk ends. The server stores nothing.
 */
class GroupSession(
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
    private val _ui = MutableStateFlow(GroupUi())
    val ui: StateFlow<GroupUi> = _ui.asStateFlow()

    val active: Boolean get() = _ui.value.phase == GroupPhase.Active

    private var settings = Settings()
    private var selfId = ""
    private var key = ""
    private var nickname = "Walker"
    private var client: GroupClient? = null
    private var code: String? = null
    private var createOptions: CreateGroupOptions? = null
    private var engine: GroupEngine? = null
    private var walkStartMs = 0L
    private val net = NetworkWatcher(app)
    private val reconnector = Reconnector(scope, net, maxAttempts = 40, stillNeeded = { !ending && active }, reconnect = { if (_ui.value.connection == SignalingState.Failed) connect() })
    private var netJob: Job? = null
    private val sendGate = SendGate()
    private val motion = MotionGate()
    private var ending = false
    private var precision = LocationPrecision.Exact
    private val walkJobs = mutableListOf<Job>()
    private var bannerJob: Job? = null
    private var goalCelebrated = false
    private var route: RouteRecorder? = null
    private var sweeperSelf = false
    private val requests = LinkedHashMap<String, String>()
    private var locJob: Job? = null
    private var recorder: SharedWalkRecorder? = null
    private var searchingSinceMs = 0L
    private var hadLocationPermission = false

    // ---------- create / join ----------

    fun create(opts: CreateGroupOptions) {
        if (_ui.value.phase != GroupPhase.Idle) return
        _ui.value = GroupUi(phase = GroupPhase.Active, iAmHost = true) // active right away, before settings are read
        scope.launch {
            begin(opts.nickname, opts.precision)
            createOptions = opts
            code = null
            _ui.update { it.copy(iAmHost = true, settings = GroupSettings(opts.approval, opts.goalSteps, opts.title.trim().take(40))) }
            connect()
        }
    }

    /** [codeOrLink] is a group link (QR or pasted) or a bare 6-character code. */
    fun join(codeOrLink: String, nicknameIn: String, precisionIn: LocationPrecision) {
        if (_ui.value.phase != GroupPhase.Idle) return
        val invite = Invites.parse(codeOrLink) as? Invite.Group
        val c = invite?.code ?: SessionCode.normalize(codeOrLink)
        if (c == null) { _ui.value = GroupUi(phase = GroupPhase.Summary, endedReason = GroupCopy.error("bad_code")); return }
        _ui.value = GroupUi(phase = GroupPhase.Active, code = c)
        scope.launch {
            begin(nicknameIn, precisionIn)
            code = c
            createOptions = null
            _ui.update { it.copy(code = c) }
            connect()
        }
    }

    private suspend fun begin(nick: String, prec: LocationPrecision) {
        settings = settingsStore.current()
        selfId = settingsStore.ensurePeerId()
        key = GroupKey.generate(SecureRandom().asKotlinRandom())
        nickname = nick.trim().ifBlank { settings.displayName.ifBlank { "Walker" } }.take(24)
        precision = prec
        reconnector.cancel(); ending = false; goalCelebrated = false; sweeperSelf = false
        requests.clear()
        engine = null
        route = if (settings.saveRoutes) RouteRecorder() else null
        recorder = null
        _ui.value = GroupUi(
            phase = GroupPhase.Active, precision = prec, quiet = settings.quietByDefault, myAvatar = settings.avatar, myId = selfId,
        )
        // Listen for the network only while a group is open; when it returns after the retries ran out, start over once.
        net.start()
        netJob?.cancel()
        netJob = scope.launch {
            launch { net.online.collect { if (it) reconnector.onNetworkBack() } }
            // The phone moved to another network: the old socket is dead even if it does not know yet. Rejoin at once with the same key.
            launch { net.changes.collect { if (active && !ending && _ui.value.connection == SignalingState.Connected) { reconnector.cancel(); client?.connect(ServerConfig.URL) } } }
        }
    }

    private fun connect() {
        val url = ServerConfig.URL
        client?.close()
        client = GroupClient(
            handleMessage = { m -> scope.launch { onMessage(m) } },
            handleState = { s, msg -> scope.launch { onConnection(s, msg) } },
        ).also { it.connect(url) }
    }

    private fun onConnection(s: SignalingState, msg: String?) {
        if (ending || _ui.value.phase != GroupPhase.Active) return
        _ui.update {
            it.copy(
                connection = s,
                note = when (s) {
                    SignalingState.Failed -> if (!reconnector.exhausted) ServerConfig.RETRY_NOTE else (msg ?: "Connection problem")
                    SignalingState.Connecting -> if (it.joined) it.note else ServerConfig.WAKING_NOTE
                    SignalingState.Connected -> null
                    else -> it.note
                },
            )
        }
        when (s) {
            SignalingState.Connected -> {
                reconnector.onConnected()
                val c = code
                val opts = createOptions
                if (c == null && opts != null) {
                    client?.send(
                        GroupClientMessage.Create(
                            peerId = selfId, key = key, name = nickname, approval = opts.approval, ttlMin = opts.ttlMin,
                            goalSteps = opts.goalSteps, title = opts.title.trim().take(40), avatar = AvatarCode.encode(settings.avatar),
                        ),
                    )
                } else if (c != null) {
                    client?.send(GroupClientMessage.Join(c, selfId, key, nickname, AvatarCode.encode(settings.avatar)))
                }
            }
            SignalingState.Failed -> reconnector.onFailed()
            else -> Unit
        }
    }

    // ---------- messages from the server ----------

    private fun onMessage(m: GroupServerMessage) {
        if (ending) return
        val now = System.currentTimeMillis()
        when (m) {
            is GroupServerMessage.Joined -> onJoined(m, now)
            is GroupServerMessage.Pending -> _ui.update { it.copy(waitingForApproval = true) }
            is GroupServerMessage.MemberJoined -> {
                engine?.onMemberJoined(m.peerId, m.name, m.avatar)
                _ui.update { u -> u.copy(roster = (u.roster.filter { it.peerId != m.peerId } + RosterEntry(m.peerId, m.name, m.host, m.avatar))) }
                // Help the newcomer see me quickly, and let them see the meeting point. A burst of joins becomes one message.
                if (sendGate.urgent(now)) sendUpdate()
                val pin = _ui.value.pin
                if (_ui.value.iAmHost && pin != null) client?.send(GroupClientMessage.Pin(pin.pos.lat, pin.pos.lon, pin.label))
            }
            is GroupServerMessage.MemberLeft -> {
                engine?.onMemberLeft(m.peerId)
                _ui.update { u -> u.copy(roster = u.roster.filter { it.peerId != m.peerId }) }
            }
            is GroupServerMessage.Upd -> engine?.onUpdate(now, m.from, m.update)
            is GroupServerMessage.SettingsChanged -> {
                _ui.update { it.copy(settings = m.settings) }
                engine?.let { e -> e.updateConfig(e.config.copy(goalSteps = m.settings.goalSteps)) }
            }
            is GroupServerMessage.PinSet -> {
                _ui.update { it.copy(pin = MeetingPin(LatLon(m.lat, m.lon), m.label)) }
                showBanner(if (m.label.isBlank()) "The host set a meeting point on the map." else "Meeting point: ${m.label}", nudge = false)
            }
            GroupServerMessage.PinCleared -> _ui.update { it.copy(pin = null) }
            is GroupServerMessage.JoinRequest -> {
                requests[m.peerId] = m.name
                publishRequests()
                if (_ui.value.iAmHost) showBanner("${m.name} would like to join", nudge = false)
            }
            is GroupServerMessage.JoinCancelled -> { requests.remove(m.peerId); publishRequests() }
            GroupServerMessage.HostAway -> _ui.update { it.copy(hostAway = true) }
            GroupServerMessage.HostBack -> _ui.update { it.copy(hostAway = false) }
            GroupServerMessage.Denied -> finish(GroupCopy.DENIED)
            GroupServerMessage.Kicked -> finish(GroupCopy.KICKED)
            GroupServerMessage.RoomClosed -> finish(GroupCopy.ROOM_CLOSED)
            GroupServerMessage.RoomExpired -> finish(GroupCopy.ROOM_EXPIRED)
            is GroupServerMessage.Error -> onServerError(m.code)
        }
    }

    private fun onServerError(c: String) {
        val fatalBeforeJoin = !_ui.value.joined && c in setOf(
            "no_such_room", "room_full", "wrong_mode", "removed", "id_taken", "too_many_joins", "too_many_rooms", "bad_code", "server_busy", "too_many_pending",
        )
        if (fatalBeforeJoin) { finish(GroupCopy.error(c)); return }
        if (c == "bad_update" || c == "not_host") return
        _ui.update { it.copy(note = GroupCopy.error(c)) }
    }

    private fun onJoined(m: GroupServerMessage.Joined, now: Long) {
        code = m.code
        _ui.update {
            it.copy(
                joined = true, waitingForApproval = false, code = m.code, iAmHost = m.host == selfId,
                inviteLink = GroupLink.build(m.code), webLink = GroupLink.webLink(m.code),
                settings = m.settings, roster = m.roster, expiresAtMs = now + m.expiresInSec * 1000L, hostAway = false,
                connection = SignalingState.Connected, note = null,
            )
        }
        if (engine == null) startTracking(now, m.settings) else engine?.let { e -> e.updateConfig(e.config.copy(goalSteps = m.settings.goalSteps)) }
        engine?.setRoster(m.roster)
        if (sendGate.urgent(now)) sendUpdate()
    }

    private fun publishRequests() = _ui.update { u -> u.copy(requests = requests.map { JoinRequestUi(it.key, it.value) }) }

    // ---------- tracking ----------

    private fun walkConfig(goal: Int) = GroupWalkConfig(
        walk = WalkConfig(
            nudge = NudgeConfig(farM = 150.0, nearM = 90.0, quiet = _ui.value.quiet),
            profile = settings.profile, caloriesEnabled = settings.caloriesEnabled, stepLengthOverrideM = settings.calibratedStepLengthM,
        ),
        group = GroupConfig(slackM = precision.maxErrorM, sweeperId = if (sweeperSelf) selfId else null),
        precision = precision, goalSteps = goal,
    )

    private fun startTracking(now: Long, gs: GroupSettings) {
        walkStartMs = now
        val e = GroupEngine(selfId, nickname, walkConfig(gs.goalSteps), now)
        engine = e
        sendGate.reset(); motion.reset()
        power.setWalk(true, groupSize = maxOf(1, _ui.value.roster.size))
        recorder = SharedWalkRecorder(now, SharedMode.Group)
        searchingSinceMs = now
        hadLocationPermission = locationSource.hasPermission()
        startLocation(e)
        walkJobs += scope.launch {
            // Same feed as the all-day counter; the walk never writes daily steps itself, so nothing is counted twice.
            steps.acquire("group")
            try {
                val start = steps.sampleNow() // also credits the steps before the walk to the day
                start?.let { e.onSelfSteps(it.baseline.tMs, it.baseline.counter) }
                val from = start?.baseline?.tMs ?: now
                steps.live.collect { r -> if (r.wallMs >= from) e.onSelfSteps(r.wallMs, r.counter) }
            } finally {
                steps.release("group")
            }
        }
        walkJobs += scope.launch { tickLoop(e) }
    }

    /** Location only while this walk is tracked, at the rate the power policy asks for (it follows screen, movement, group size, savers). */
    private fun startLocation(e: GroupEngine) {
        locJob?.cancel()
        locJob = scope.launch {
            locationSource.fixes(power.locationPlans()).collect { fix ->
                e.onSelfFix(fix)
                route?.add(System.currentTimeMillis(), fix.pos, fix.accuracyM)
            }
        }
        locJob?.let { walkJobs += it }
    }

    /** The person just allowed location (or switched it on) while the walk runs: start listening again. */
    fun onLocationAvailable() {
        val e = engine ?: return
        if (active && !_ui.value.demo) startLocation(e)
    }

    private fun locationStatus(st: GroupState, now: Long): Pair<LocationStatus, Long> {
        val perm = locationSource.hasPermission()
        if (perm && !hadLocationPermission) engine?.let { startLocation(it) }
        hadLocationPermission = perm
        val status = LocationStatusLogic.of(perm, locationSource.anyProviderOn(), st.locationQuality, locationSource.hasPrecisePermission())
        if (status != LocationStatus.Searching) searchingSinceMs = now
        return status to ((now - searchingSinceMs) / 1000L).coerceAtLeast(0)
    }

    private fun recordShared(st: GroupState, now: Long) {
        val rec = recorder ?: return
        if (st.members.isEmpty()) return
        val walkers = TrackBuilders.forGroup(st, nickname, settings.avatar)
        val lanes = TrackBuilders.recLanes(walkers, { w -> AvatarCode.encode(w.avatar) }, { w -> if (w.isMe) st.myDistanceM else null })
        val xs = walkers.mapNotNull { it.xM }
        rec.onFrame(now, lanes, if (xs.size >= 2) xs.max() - xs.min() else null)
    }

    private suspend fun tickLoop(e: GroupEngine) {
        while (true) {
            // The loop pace follows the power policy: every second with the screen on, every few seconds in a pocket.
            delay(power.plan().tickMs)
            val now = System.currentTimeMillis()
            val st = e.tick(now)
            power.setMoving(motion.update(now, st.mySpeedMps))
            power.setGroupSize(st.memberCount)
            if (sendGate.poll(now, power.plan().sendIntervalMs)) sendUpdate()
            val (locSt, locSec) = locationStatus(st, now)
            recordShared(st, now)
            _ui.update { it.copy(walk = st, groupSteps = st.goal.totalSteps, locStatus = locSt, locSearchingSec = locSec) }
            st.nudge?.let { showBanner(it.text, nudge = true) }
            if (st.goal.reached && !goalCelebrated && st.goal.goal > 0) {
                goalCelebrated = true
                showBanner(com.walkbuddy.domain.CollectiveSteps.message(st.goal), nudge = false)
            }
        }
    }

    /** User-driven changes (pause sharing) go out now, but never more than once per short gap. */
    private fun sendUpdateNow() {
        if (sendGate.urgent(System.currentTimeMillis())) sendUpdate()
    }

    private fun sendUpdate() {
        val e = engine ?: return
        val c = client ?: return
        if (!_ui.value.joined || _ui.value.connection != SignalingState.Connected) return
        val now = System.currentTimeMillis()
        val u = e.selfUpdate(now)
        // "Pause sharing" keeps my steps in the group total but sends no position at all.
        val out = if (_ui.value.sharing) u else GroupUpdate(now, null, null, steps = u.steps)
        c.send(GroupClientMessage.Update(out))
    }

    private fun showBanner(text: String, nudge: Boolean) {
        Notifications.haptic(app)
        if (nudge && !settings.quietHours.isQuiet(Clock.hourOfDay())) Notifications.nudge(app, text)
        _ui.update { it.copy(banner = text) }
        bannerJob?.cancel()
        bannerJob = scope.launch { delay(12_000); _ui.update { it.copy(banner = null) } }
    }

    fun dismissBanner() = _ui.update { it.copy(banner = null) }

    // ---------- my controls ----------

    fun setSharing(on: Boolean) {
        _ui.update { it.copy(sharing = on) }
        sendUpdateNow()
    }

    fun setQuiet(on: Boolean) {
        _ui.update { it.copy(quiet = on) }
        val e = engine ?: return
        e.updateConfig(e.config.copy(walk = e.config.walk.copy(nudge = e.config.walk.nudge.copy(quiet = on))))
    }

    /** Local choice: "I am walking at the back on purpose", so I do not get catch-up hints. */
    fun setSweeper(on: Boolean) {
        sweeperSelf = on
        _ui.update { it.copy(iAmSweeper = on) }
        val e = engine ?: return
        e.updateConfig(e.config.copy(group = e.config.group.copy(sweeperId = if (on) selfId else null)))
    }

    // ---------- host controls ----------

    fun approve(id: String) { client?.send(GroupClientMessage.Approve(id)); requests.remove(id); publishRequests() }
    fun deny(id: String) { client?.send(GroupClientMessage.Deny(id)); requests.remove(id); publishRequests() }
    fun kick(id: String) { client?.send(GroupClientMessage.Kick(id)) }
    fun setApproval(on: Boolean) { client?.send(GroupClientMessage.ChangeSettings(approval = on)) }
    fun setGoal(steps: Int) { client?.send(GroupClientMessage.ChangeSettings(goalSteps = steps.coerceIn(0, 10_000_000))) }

    fun setPin(pos: LatLon, label: String) {
        if (!_ui.value.iAmHost) return
        val pin = MeetingPin(pos, label.trim().take(40))
        _ui.update { it.copy(pin = pin) }
        client?.send(GroupClientMessage.Pin(pos.lat, pos.lon, pin.label))
    }

    fun clearPin() {
        if (!_ui.value.iAmHost) return
        _ui.update { it.copy(pin = null) }
        client?.send(GroupClientMessage.Unpin)
    }

    // ---------- ending ----------

    /**
     * Ends my walk and shows the summary. A host can also close the room for everyone ([closeForEveryone]); if they do not,
     * the group carries on and the host can return to it for a while.
     */
    fun endWalk(closeForEveryone: Boolean = false) {
        if (_ui.value.demo) { endDemo(); return }
        if (closeForEveryone && _ui.value.iAmHost) client?.send(GroupClientMessage.CloseRoom)
        finish(null, leaveRoom = !closeForEveryone)
    }

    /** Leave before anything happened (waiting screen, errors). Nothing is saved. */
    fun cancel() {
        if (_ui.value.demo) { stopDemo(); return }
        ending = true
        walkJobs.forEach { it.cancel() }; walkJobs.clear()
        client?.send(GroupClientMessage.Leave)
        teardown()
        _ui.value = GroupUi()
    }

    private fun finish(reason: String?, leaveRoom: Boolean = true) {
        if (ending) return
        ending = true
        val e = engine
        val wasJoined = _ui.value.joined
        // teardown() clears these; the saving below runs after it.
        val finishedRoute = route
        val finishedRecorder = recorder
        if (leaveRoom && wasJoined) client?.send(GroupClientMessage.Leave)
        walkJobs.forEach { it.cancel() }; walkJobs.clear()
        teardown()
        if (e == null) {
            _ui.value = GroupUi(phase = GroupPhase.Summary, endedReason = reason)
            return
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val summary = e.finish(now)
            val groupSteps = _ui.value.groupSteps
            var walkId: Long? = null
            if (summary.durationMs >= 20_000) {
                // Saved like a solo walk, so a group never counts as a couple walk in "Our week" or the couple odometer.
                walkId = repo.saveWalk(summary.copy(buddyCount = 0, togetherPct = null, longestTogetherMs = 0), walkStartMs)
                if (settings.healthConnectOn) health.writeWalk(walkStartMs, now, summary.verifiedSteps, summary.distanceM)
            }
            finishedRoute?.let { r -> if (settings.saveRoutes) routes.save(walkStartMs, r.points) }
            saveSharedWalk(finishedRecorder, finishedRoute, summary, now)
            val card = Highlights.build(summary, showCalories = settings.caloriesEnabled)
            engine = null
            _ui.value = GroupUi(
                phase = GroupPhase.Summary, summary = summary, highlights = card, endedReason = reason, groupSteps = groupSteps, walkId = walkId,
                precision = precision,
            )
        }
    }

    /** Keeps the replay of a group walk for the "Walks together" history. A failure here must never lose the summary. */
    private suspend fun saveSharedWalk(rec: SharedWalkRecorder?, route: RouteRecorder?, summary: WalkSummary, endMs: Long) {
        if (rec == null) return
        if (rec.laneCount < 2 || endMs - walkStartMs < 60_000L) return
        runCatching {
            val st = _ui.value.walk
            val lastFrame = st?.let { s ->
                TrackBuilders.recLanes(TrackBuilders.forGroup(s, nickname, settings.avatar), { w -> AvatarCode.encode(w.avatar) }, { w -> if (w.isMe) summary.distanceM else null })
            }
            val points = route?.points.orEmpty().map { LatLon(it.lat, it.lon) }
            val poly = if (settings.saveRoutes && points.size >= 2) Polyline.encode(Polyline.simplify(points)) else null
            val title = _ui.value.settings.title.ifBlank { "Group walk" }
            val record = rec.build(
                endMs = endMs, title = title, memberCount = maxOf(rec.laneCount, st?.memberCount ?: 0), togetherPct = st?.together?.scorePct ?: 0,
                longestTogetherMs = st?.together?.longestStreakMs ?: 0, route = poly, lastFrame = lastFrame,
            )
            repo.saveSharedWalk(record)
        }
    }

    fun finishSummary() { _ui.value = GroupUi() }

    private fun teardown() {
        // The walk is over: stop location and fast sensors (the policy sees walkActive=false), drop the socket and the network callback.
        power.setWalk(false)
        reconnector.cancel()
        netJob?.cancel(); netJob = null
        net.stop()
        client?.close(); client = null
        requests.clear()
        route = null; recorder = null
    }

    // ---------- demo group (no server, no permissions, nothing saved) ----------

    /** A scripted walk with eight pretend walkers, a late joiner, a straggler and someone leaving, to explore the screens anywhere. */
    fun startDemo() {
        if (_ui.value.phase != GroupPhase.Idle) return
        val start = System.currentTimeMillis()
        walkStartMs = start
        selfId = "me"; nickname = "You"; ending = false; goalCelebrated = false; sweeperSelf = false
        val origin = LatLon(12.9716, 77.5946)
        val goal = 20_000
        val e = GroupEngine("me", "You", GroupWalkConfig(goalSteps = goal), start)
        engine = e
        e.setRoster(listOf(RosterEntry("me", "You", true)))
        val names = listOf("Asha", "Ben", "Chitra", "Dev", "Esha", "Farid", "Gita", "Hari")
        val pinPos = metres(origin, 380.0, 0.0)
        _ui.value = GroupUi(
            phase = GroupPhase.Active, demo = true, iAmHost = true, joined = true, code = "DEMO42", connection = SignalingState.Connected,
            settings = GroupSettings(false, goal, "Demo group walk"), pin = MeetingPin(pinPos, "Chai stop"),
            roster = listOf(RosterEntry("me", "You", true)), expiresAtMs = start + 2 * 3600_000L,
        )
        walkJobs += scope.launch {
            var sec = 0
            while (true) {
                delay(1_000)
                sec++
                val now = start + sec * 1000L
                val me = trackPoint(origin, sec * 1.35)
                e.onSelfFix(Fix(now, me.lat, me.lon, 5.0, 1.35))
                e.onSelfSteps(now, sec * 2L)
                for ((i, name) in names.withIndex()) {
                    val joinAt = if (i == 7) 40 else 1 // Hari joins mid-walk
                    if (sec < joinAt) continue
                    if (sec == joinAt) {
                        e.onMemberJoined("d$i", name)
                        _ui.update { u -> u.copy(roster = u.roster + RosterEntry("d$i", name, false)) }
                    }
                    if (i == 5 && sec > 150) { if (sec == 151) e.onMemberLeft("d$i"); continue } // Farid heads home
                    var along = (i - 3) * 7.0 + sin(sec / 11.0 + i) * 9.0
                    // Dev drifts back for a while, then catches up.
                    if (i == 3) along -= when { sec < 60 -> 0.0; sec < 130 -> (sec - 60) * 3.2; sec < 170 -> 224.0 - (sec - 130) * 5.5; else -> 4.0 }
                    val lateral = ((i % 3) - 1) * 6.0
                    val p = trackPoint(origin, sec * 1.35 + along, lateral)
                    e.onUpdate(now, "d$i", GroupUpdate(now, p.lat, p.lon, 6.0, 1.3, (sec * 1.9 + i * 11).toInt(), 108.0, sec * 1.3))
                }
                val st = e.tick(now)
                _ui.update { it.copy(walk = st, groupSteps = st.goal.totalSteps) }
                st.nudge?.let { n -> _ui.update { u -> u.copy(banner = n.text) } }
                if (sec % 25 == 0) _ui.update { it.copy(banner = null) }
            }
        }
    }

    private fun endDemo() {
        val e = engine
        walkJobs.forEach { it.cancel() }; walkJobs.clear()
        val now = System.currentTimeMillis()
        val summary = e?.finish(now)
        val card = summary?.let { Highlights.build(it, false) }
        val total = _ui.value.groupSteps
        engine = null
        ending = true
        _ui.value = GroupUi(phase = GroupPhase.Summary, demo = true, summary = summary, highlights = card, groupSteps = total)
    }

    private fun stopDemo() {
        walkJobs.forEach { it.cancel() }; walkJobs.clear()
        engine = null
        _ui.value = GroupUi()
    }

    /** A point [s] metres along a gentle route (north, then east) and [lateral] metres to its right. */
    private fun trackPoint(o: LatLon, s: Double, lateral: Double = 0.0): LatLon {
        val leg = 420.0
        if (s <= leg) return metres(o, s, lateral)
        return metres(metres(o, leg, 0.0), -lateral, s - leg)
    }

    /** Offsets [north] and [east] metres from a point (flat-earth, fine for a few hundred metres). */
    private fun metres(o: LatLon, north: Double, east: Double): LatLon {
        val dLat = north / 111_195.0
        val dLon = east / (111_195.0 * cos(Math.toRadians(o.lat)))
        return LatLon(o.lat + dLat, o.lon + dLon)
    }
}
