package com.bitchat.repo.repositories

import com.bitchat.client.httpEngineSupportsTorProxy
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.domain.tor.repository.TorRepository
import com.bitchat.local.prefs.TorPreferences
import com.bitchat.tor.TorManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * The single place that decides what the rest of the app is told about Tor.
 *
 * When [engineSupportsTorProxy] is false the HTTP engine of this build ignores the SOCKS proxy, so
 * a running Tor protects exactly nothing. Every read here reports Tor as off and unavailable in
 * that case, which is what keeps the header dot, the status card and the settings switch from
 * showing protection that does not exist. The stored preference is deliberately left alone: only
 * what is *reported* is forced off, so the same profile still turns Tor on in a build whose engine
 * can proxy.
 */
class TorRepo(
    private val torManager: TorManager,
    private val torPreferences: TorPreferences,
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val torEventBus: TorEventBus,
    private val engineSupportsTorProxy: Boolean = httpEngineSupportsTorProxy,
) : TorRepository {
    @Volatile
    private var externalLogLine: String? = null

    /**
     * True when this process tried to honour a stored ON and Tor did not come up.
     *
     * Stored intent and effective state are deliberately different things. The intent is the
     * user's, lives in [torPreferences] and is only ever rewritten by the user: a failed start
     * that overwrote it with OFF would mean that repairing the installation, or restarting after
     * a transient failure, left Tor silently disabled with nothing to explain why. The effective
     * state is what this process can actually deliver, and it is what the settings switch and the
     * status card read - a Tor that refused to start must not be shown as on.
     *
     * Process-scoped on purpose: the next launch retries the stored ON with a clean slate.
     */
    @Volatile
    private var startFailed: Boolean = false

    /** True when Tor would bootstrap for nothing, because no request can be routed through it. */
    private val proxyingImpossible: Boolean get() = !engineSupportsTorProxy

    init {
        coroutineScopeFacade.applicationScope.launch {
            torManager.statusFlow
                .collect {
                    torEventBus.update(TorEvent.StatusChanged)
                }
        }
    }

    override suspend fun getSocksProxyAddress(): Pair<String, Int>? = withContext(coroutinesContextFacade.io) {
        if (proxyingImpossible) null else torManager.getSocksProxyAddress()
    }

    override fun isProxyReady(): Boolean {
        // "Ready" has to mean "traffic goes through it", not "Arti answered on 9050".
        return !proxyingImpossible && torManager.isProxyReady()
    }

    override fun torAvailability(): TorAvailability = when {
        // Checked first: on Apple and Linux the Arti library loads fine, so the honest reason the
        // switch is dead is the engine, not the library.
        proxyingImpossible -> TorAvailability.NO_PROXY_SUPPORT
        !torManager.isAvailable -> TorAvailability.NATIVE_LIBRARY_MISSING
        else -> TorAvailability.AVAILABLE
    }

    override suspend fun enable() = withContext(coroutinesContextFacade.io) {
        if (proxyingImpossible) {
            // Nothing to gain and memory and CPU to lose: Arti would bootstrap a circuit no
            // request can use. The preference is not written either way, so a build that can
            // proxy still starts from whatever the user last chose there.
            println("TorRepo: not starting Tor - this build's HTTP engine cannot use a SOCKS proxy")
            return@withContext
        }
        torManager.start()
        // start() reports failure through statusFlow rather than throwing.
        val started = torManager.isAvailable &&
                torManager.statusFlow.value.state != TorState.ERROR
        if (started) {
            startFailed = false
            // Written only now that Tor is actually up, so a persisted ON always means "this
            // worked" and the next launch is right to start it again.
            torPreferences.setTorMode(TorMode.ON)
        } else {
            // The stored intent is left exactly as it was: erasing it here would turn one failed
            // start - a missing library, a busy port - into Tor being off for good, silently.
            // Only the *reported* mode goes off, via [startFailed] below.
            startFailed = true
            // Nothing else announces "we could not turn Tor on", and the settings switch follows
            // the reported mode, so it would otherwise stay visibly on.
            torEventBus.update(TorEvent.ModeChanged)
        }
    }

    override suspend fun disable() = withContext(coroutinesContextFacade.io) {
        torManager.stop()
        startFailed = false
        torPreferences.setTorMode(TorMode.OFF)
    }

    override suspend fun getTorStatus(): TorStatus = withContext(coroutinesContextFacade.io) {
        if (proxyingImpossible) {
            // Whatever the manager says - and it can still say RUNNING at 100% if something else
            // started it - none of it protects the user here, and the header dot and status card
            // are driven straight off this.
            return@withContext TorStatus()
        }
        val status = torManager.statusFlow.value
        if (status.state == TorState.ERROR) {
            // A relay log line would mask the one message that explains why Tor is broken, and
            // relay lines keep arriving while Tor is down because traffic falls back to direct.
            status
        } else {
            status.copy(lastLogLine = externalLogLine ?: status.lastLogLine)
        }
    }

    /** The effective mode. Reported off, never written off - see [startFailed]. */
    override suspend fun getTorMode(): TorMode = withContext(coroutinesContextFacade.io) {
        when {
            // A bootstrapped Tor protects nothing in this build, so showing the switch on would
            // read as protection. The stored ON survives for a build whose engine can proxy.
            proxyingImpossible -> TorMode.OFF
            // Tor was asked for and did not come up. The card explains why; the switch must not
            // claim otherwise.
            startFailed -> TorMode.OFF
            else -> torPreferences.getTorMode()
        }
    }

    /** The stored intent, exactly as the user left it. [TorAppInitializer] starts from this. */
    override suspend fun getStoredTorMode(): TorMode = withContext(coroutinesContextFacade.io) {
        torPreferences.getTorMode()
    }

    override suspend fun setTorMode(mode: TorMode) = withContext(coroutinesContextFacade.io) {
        // An explicit choice by the user replaces whatever the last start attempt concluded.
        startFailed = false
        torPreferences.setTorMode(mode)
    }

    fun recordExternalLogLine(line: String) {
        externalLogLine = line
        coroutineScopeFacade.applicationScope.launch {
            torEventBus.update(TorEvent.StatusChanged)
        }
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        disable()
        torPreferences.setTorMode(TorMode.OFF)
    }
}
