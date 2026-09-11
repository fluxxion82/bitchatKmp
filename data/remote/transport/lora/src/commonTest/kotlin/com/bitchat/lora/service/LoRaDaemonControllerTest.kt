package com.bitchat.lora.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoRaDaemonControllerTest {
    private class Backend : LoRaDaemonBackend {
        val states = LoRaDaemon.entries.associateWith { DaemonSnapshot(true, "inactive", false, false) }.toMutableMap()
        val commands = mutableListOf<Pair<LoRaDaemon, DaemonAction>>()
        var clock = 0L
        var stopDenied = false
        var stopStuck = false
        var startListens = true
        var startReplyFails = false
        var resetFailedDenied = false
        var snapshotCostMs = 0L
        var executeCostMs = 0L
        var listenerCostMs = 0L
        val listeners = mutableSetOf<LoRaDaemon>()
        private fun spend(costMs: Long, deadlineMs: Long): Boolean {
            clock += minOf(costMs, maxOf(0, deadlineMs - clock))
            return clock < deadlineMs
        }
        override fun snapshot(daemon: LoRaDaemon, deadlineMs: Long): DaemonSnapshot {
            if (!spend(snapshotCostMs, deadlineMs)) return states.getValue(daemon).copy(known = false, detail = "status timed out")
            return states.getValue(daemon)
        }
        override fun execute(daemon: LoRaDaemon, action: DaemonAction, deadlineMs: Long): DaemonResult {
            if (!spend(executeCostMs, deadlineMs)) return DaemonResult(false, "command timed out")
            commands += daemon to action
            if (action == DaemonAction.STOP && stopDenied) return DaemonResult(false, "permission denied")
            when (action) {
                DaemonAction.STOP -> if (!stopStuck) {
                    states[daemon] = DaemonSnapshot(true, "inactive", false, false)
                    listeners -= daemon
                }
                DaemonAction.TERMINATE -> states[daemon] = states.getValue(daemon).copy(processAlive = false)
                DaemonAction.START -> {
                    states[daemon] = DaemonSnapshot(true, "active", false, true)
                    if (startListens) listeners += daemon
                    if (startReplyFails) return DaemonResult(false, "start reply timed out")
                }
                DaemonAction.RESET_FAILED -> if (resetFailedDenied) return DaemonResult(false, "unit not loaded")
            }
            return DaemonResult(true)
        }
        override fun isListening(daemon: LoRaDaemon, deadlineMs: Long): Boolean {
            return spend(listenerCostMs, deadlineMs) && daemon in listeners
        }
        override fun nowMs() = clock
        override fun pause(ms: Long) { clock += ms }
    }

    private fun controller(backend: Backend) = LoRaDaemonController(backend, stopTimeoutMs = 300, startupTimeoutMs = 400, pollMs = 50)

    @Test fun activatingConflictIsStoppedBeforeTargetStart() {
        val b = Backend()
        b.states[LoRaDaemon.MESHTASTIC] = DaemonSnapshot(true, "activating", true, false)
        assertTrue(controller(b).start(LoRaDaemon.MESHCORE).success)
        assertTrue(b.commands.indexOf(LoRaDaemon.MESHTASTIC to DaemonAction.STOP) < b.commands.indexOf(LoRaDaemon.MESHCORE to DaemonAction.START))
    }

    @Test fun stopDeniedPreventsStartingAnotherDaemon() {
        val b = Backend()
        b.states[LoRaDaemon.MESHTASTIC] = DaemonSnapshot(true, "active", false, true)
        b.stopDenied = true
        assertFalse(controller(b).start(LoRaDaemon.MESHCORE).success)
        assertFalse(b.commands.any { it.second == DaemonAction.START })
    }

    @Test fun stillStoppingIsFailureRatherThanSuccess() {
        val b = Backend()
        b.states[LoRaDaemon.MESHCORE] = DaemonSnapshot(true, "deactivating", true, true)
        b.stopStuck = true
        assertFalse(controller(b).stop(LoRaDaemon.MESHCORE).success)
        assertTrue(b.clock <= 350)
    }

    @Test fun unknownOwnershipFailsClosed() {
        val b = Backend()
        b.states[LoRaDaemon.MESHTASTIC] = DaemonSnapshot(false, "unknown", false, false, known = false)
        assertFalse(controller(b).prepareDirectRadio().success)
        assertTrue(b.commands.isEmpty())
    }

    @Test fun missingUnitDoesNotHideManualProcess() {
        val b = Backend()
        b.states[LoRaDaemon.MESHCORE] = DaemonSnapshot(false, "inactive", false, true)
        assertTrue(controller(b).prepareDirectRadio().success)
        assertTrue(LoRaDaemon.MESHCORE to DaemonAction.TERMINATE in b.commands)
        assertFalse(b.commands.any { it.second == DaemonAction.START })
    }

    @Test fun missingTargetUnitIsNotLaunchedInBackground() {
        val b = Backend()
        b.states[LoRaDaemon.MESHCORE] = DaemonSnapshot(false, "inactive", false, false)
        assertFalse(controller(b).start(LoRaDaemon.MESHCORE).success)
        assertFalse(b.commands.any { it.second == DaemonAction.START })
    }

    @Test fun listenerTimeoutStopsAttemptAndExplicitRetryCanSucceed() {
        val b = Backend()
        val c = controller(b)
        b.startListens = false
        assertFalse(c.start(LoRaDaemon.MESHCORE).success)
        assertFalse(b.states.getValue(LoRaDaemon.MESHCORE).processAlive)
        b.startListens = true
        assertTrue(c.start(LoRaDaemon.MESHCORE).success)
    }

    @Test fun directRadioStopsBothDaemonsAndLegacyService() {
        val b = Backend()
        for (daemon in LoRaDaemon.entries) b.states[daemon] = DaemonSnapshot(true, "active", false, true)
        assertTrue(controller(b).prepareDirectRadio().success)
        assertTrue(b.states.values.none { it.processAlive || it.hasJob })
        assertFalse(b.commands.any { it.second == DaemonAction.START })
    }

    @Test fun failedStartReplyStillStopsAnAcceptedStartJob() {
        val b = Backend()
        b.startReplyFails = true
        assertFalse(controller(b).start(LoRaDaemon.MESHCORE).success)
        assertTrue(b.states.getValue(LoRaDaemon.MESHCORE).stopped)
        assertTrue(LoRaDaemon.MESHCORE to DaemonAction.STOP in b.commands)
    }

    @Test fun stopBudgetIncludesInitialSnapshotAndStopCommand() {
        val b = Backend()
        b.states[LoRaDaemon.MESHCORE] = DaemonSnapshot(true, "active", false, true)
        b.snapshotCostMs = 200
        b.executeCostMs = 200
        assertFalse(controller(b).stop(LoRaDaemon.MESHCORE).success)
        assertTrue(b.clock <= 300, "stop exceeded its whole-operation budget: ${b.clock}")
    }

    @Test fun slowListenerCannotReportReadinessAfterStartupDeadline() {
        val b = Backend()
        b.states[LoRaDaemon.MESHCORE] = DaemonSnapshot(true, "active", false, true)
        b.listeners += LoRaDaemon.MESHCORE
        b.listenerCostMs = 500
        assertFalse(controller(b).start(LoRaDaemon.MESHCORE).success)
        assertTrue(b.clock <= 400 + 300, "startup plus cleanup exceeded their budgets: ${b.clock}")
        assertTrue(b.states.getValue(LoRaDaemon.MESHCORE).stopped)
    }

    @Test fun healthyInactiveTargetStartsWithoutResetFailed() {
        val b = Backend()
        b.resetFailedDenied = true
        assertTrue(controller(b).start(LoRaDaemon.MESHCORE).success)
        assertFalse(LoRaDaemon.MESHCORE to DaemonAction.RESET_FAILED in b.commands)
    }

    @Test fun deliberateRetryResetsFailedTargetBeforeStarting() {
        val b = Backend()
        b.states[LoRaDaemon.MESHCORE] = DaemonSnapshot(true, "failed", false, false)
        assertTrue(controller(b).start(LoRaDaemon.MESHCORE).success)
        val resetIndex = b.commands.indexOf(LoRaDaemon.MESHCORE to DaemonAction.RESET_FAILED)
        val startIndex = b.commands.indexOf(LoRaDaemon.MESHCORE to DaemonAction.START)
        assertTrue(resetIndex >= 0 && resetIndex < startIndex)
    }
}
