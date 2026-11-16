package io.github.onlinemo.meloxwatch.playback.sleep

import android.os.Bundle
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreIssue
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class SleepTimerSessionContractTest {
    @Test fun serviceWaitingStateRoundTripsWithoutExposingBootOrTrackIdentities() {
        val binding = SleepTimerTrackBinding("private-session", "private-queue", "private-entry", 7L)
        val active = ActiveSleepTimer(token(), 1_000L, 2, SleepTimerEndMode.AFTER_CURRENT_TRACK, binding)
        val observed = SleepTimerSessionState.fromService(ServiceSleepTimerState(
            timer = SleepTimerState(active = active), initialized = true,
            persistence = SleepTimerPersistence.SAVING, summaryEnabled = true,
            notificationAllowed = false,
        ))
        val expected = SleepTimerSessionState(
            initialized = true, generationId = GENERATION, active = true, minutes = 2,
            deadlineElapsedMs = 121_000L, endMode = SleepTimerEndMode.AFTER_CURRENT_TRACK,
            waitingForTrackEnd = true, persistence = SleepTimerPersistence.SAVING,
            summaryEnabled = true, notificationAllowed = false,
        )
        assertEquals(expected, observed)
        val extras = observed.toSessionExtras()
        assertEquals(expected, SleepTimerSessionState.fromSessionExtras(extras))
        assertEquals(setOf("schema", "initialized", "generation", "active", "minutes", "deadline",
            "mode", "waiting", "persistence", "conflict", "summary", "notificationAllowed")
            .map { PREFIX + it }.toSet(), extras.keySet())
        val exposed = extras.keySet().mapNotNull { extras.raw(it) as? String }
        assertFalse(exposed.any { it in setOf("private-boot", binding.sessionId,
            binding.queueGenerationId, binding.queueEntryId) })
    }

    @Test fun stoppedFailureObservationPreservesTheGenerationAndPersistenceEvidence() {
        val state = ServiceSleepTimerState(
            timer = SleepTimerState(lastStop = SleepTimerStop(token(), SleepTimerStopReason.USER_CANCELLED)),
            initialized = true, persistence = SleepTimerPersistence.FAILED,
            issue = SleepTimerStoreIssue.IO, conflict = true,
            recovery = SleepTimerStoreRecovery.CORRUPT_FILE, summaryEnabled = true,
            notificationAllowed = false,
        )
        val expected = SleepTimerSessionState(
            initialized = true, generationId = GENERATION, stopReason = SleepTimerStopReason.USER_CANCELLED,
            persistence = SleepTimerPersistence.FAILED, issue = SleepTimerStoreIssue.IO, conflict = true,
            recovery = SleepTimerStoreRecovery.CORRUPT_FILE, summaryEnabled = true,
            notificationAllowed = false,
        )
        val extras = SleepTimerSessionState.fromService(state).toSessionExtras()
        assertEquals(expected, SleepTimerSessionState.fromSessionExtras(extras))
        listOf("minutes", "deadline", "mode").forEach { assertFalse(extras.containsKey(PREFIX + it)) }
    }

    @Test fun loadingObservationAndUnrelatedSessionDomainsRemainUsable() {
        val observed = SleepTimerSessionState.fromService(ServiceSleepTimerState())
        assertEquals(SleepTimerSessionState(), observed)
        val extras = observed.toSessionExtras().apply {
            putString("io.github.onlinemo.meloxwatch.playback.state", "independent-domain")
        }
        assertEquals(observed, SleepTimerSessionState.fromSessionExtras(extras))
        assertEquals("independent-domain", extras.getString("io.github.onlinemo.meloxwatch.playback.state"))
    }

    @Test fun remainingMinutesUseCeilingAndClampUntrustedClockValues() {
        val state = activeObservation()
        assertEquals(2, state.remainingMinutes(1_000L))
        assertEquals(2, state.remainingMinutes(60_999L))
        assertEquals(1, state.remainingMinutes(61_000L))
        assertEquals(1, state.remainingMinutes(120_999L))
        assertEquals(0, state.remainingMinutes(121_000L))
        assertEquals(0, state.remainingMinutes(Long.MAX_VALUE))
        assertEquals(2, state.remainingMinutes(Long.MIN_VALUE))
        assertEquals(0, SleepTimerSessionState().remainingMinutes(0L))
        val boundary = state.copy(minutes = 180, deadlineElapsedMs = Long.MAX_VALUE)
        assertEquals(180, boundary.remainingMinutes(Long.MIN_VALUE))
        assertEquals(1, boundary.remainingMinutes(Long.MAX_VALUE - 1L))
    }

    @Test fun missingOrNullRequiredObservationFieldsCannotBecomeDefaultValues() {
        REQUIRED_FIELDS.forEach { field ->
            assertNull("missing $field", SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
                remove(PREFIX + field)
            }))
            assertNull("null $field", SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
                putString(PREFIX + field, null)
            }))
        }
    }

    @Test fun everyObservationFieldRejectsTheWrongOriginalBundleType() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "schema" to { putLong(PREFIX + "schema", 1L) },
            "initialized" to { putInt(PREFIX + "initialized", 1) },
            "generation" to { putLong(PREFIX + "generation", 1L) },
            "active" to { putString(PREFIX + "active", "true") },
            "minutes" to { putLong(PREFIX + "minutes", 2L) },
            "deadline" to { putInt(PREFIX + "deadline", 121_000) },
            "mode" to { putInt(PREFIX + "mode", 1) },
            "waiting" to { putString(PREFIX + "waiting", "false") },
            "stop" to { putInt(PREFIX + "stop", 1) },
            "persistence" to { putInt(PREFIX + "persistence", 1) },
            "issue" to { putInt(PREFIX + "issue", 1) },
            "conflict" to { putInt(PREFIX + "conflict", 0) },
            "recovery" to { putBoolean(PREFIX + "recovery", true) },
            "summary" to { putString(PREFIX + "summary", "true") },
            "notificationAllowed" to { putInt(PREFIX + "notificationAllowed", 1) },
        )
        mutations.forEach { (field, mutate) ->
            assertNull(field, SleepTimerSessionState.fromSessionExtras(activeExtras().apply(mutate)))
        }
    }

    @Test fun unsupportedObservationSchemasAndUnknownEnumNamesAreRejected() {
        listOf(-1, 0, 2, Int.MAX_VALUE).forEach { schema ->
            assertNull("schema $schema", SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
                putInt(PREFIX + "schema", schema)
            }))
        }
        val fields = listOf("persistence", "issue", "recovery", "stop")
        fields.forEach { field ->
            val base = SleepTimerSessionState(initialized = true, generationId = GENERATION).toSessionExtras()
            assertNotNull(SleepTimerSessionState.fromSessionExtras(base))
            assertNull(field, SleepTimerSessionState.fromSessionExtras(base.apply {
                putString(PREFIX + field, "UNKNOWN_VALUE")
            }))
        }
        assertNull(SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
            putString(PREFIX + "mode", "UNKNOWN_VALUE")
        }))
    }

    @Test fun activeObservationsRequireCompleteCanonicalAndConsistentTimerFields() {
        listOf("generation", "minutes", "deadline", "mode").forEach { field ->
            assertNull(field, SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
                remove(PREFIX + field)
            }))
        }
        INVALID_GENERATIONS.forEach { generation ->
            assertNull(generation, SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
                putString(PREFIX + "generation", generation)
            }))
        }
        listOf(0, 181).forEach { minutes ->
            assertNull(SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
                putInt(PREFIX + "minutes", minutes)
            }))
        }
        assertNull(SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
            putLong(PREFIX + "deadline", -1L)
        }))
        assertNull(SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
            putString(PREFIX + "stop", SleepTimerStopReason.USER_CANCELLED.name)
        }))
        assertNull(SleepTimerSessionState.fromSessionExtras(activeExtras().apply {
            putString(PREFIX + "mode", SleepTimerEndMode.PAUSE_IMMEDIATELY.name)
            putBoolean(PREFIX + "waiting", true)
        }))
    }

    @Test fun inactiveObservationsCannotCarryActiveOrWaitingFields() {
        val mutations: List<Bundle.() -> Unit> = listOf(
            { putInt(PREFIX + "minutes", 1) },
            { putLong(PREFIX + "deadline", 0L) },
            { putString(PREFIX + "mode", SleepTimerEndMode.AFTER_CURRENT_TRACK.name) },
            { putBoolean(PREFIX + "waiting", true) },
        )
        mutations.forEach { mutate ->
            assertNull(SleepTimerSessionState.fromSessionExtras(SleepTimerSessionState().toSessionExtras().apply(mutate)))
        }
    }

    @Test fun everyStopReasonRetainsItsOriginalGenerationThroughTheWire() {
        SleepTimerStopReason.entries.forEach { reason ->
            val service = ServiceSleepTimerState(timer = SleepTimerState(lastStop = SleepTimerStop(token(), reason)),
                initialized = true, persistence = SleepTimerPersistence.SAVED)
            val observed = requireNotNull(SleepTimerSessionState.fromSessionExtras(
                SleepTimerSessionState.fromService(service).toSessionExtras()))
            assertEquals(reason, observed.stopReason)
            assertEquals(GENERATION, observed.generationId)
            assertFalse(observed.active)
        }
    }

    @Test fun armCommandsRoundTripBothModesAndDurationLimitsWithNullableExpectedGeneration() {
        listOf(1, 180).forEach { minutes ->
            SleepTimerEndMode.entries.forEach { mode ->
                listOf(null, GENERATION).forEach { expected ->
                    val arguments = SleepTimerCommands.armArguments(minutes, mode, expected)
                    assertEquals(SleepTimerArmCommand(minutes, mode, expected), SleepTimerCommands.readArm(arguments))
                    assertTrue(arguments.containsKey("expected_generation"))
                    assertEquals(1, arguments.raw("schema"))
                    assertEquals(expected, arguments.raw("expected_generation"))
                }
            }
        }
    }

    @Test fun cancelAndRetryArgumentsPreserveAnExplicitNullRatherThanAMissingGeneration() {
        listOf(null, GENERATION).forEach { generation ->
            val arguments = SleepTimerCommands.expectedArguments(generation)
            assertEquals(setOf("schema", "expected_generation"), arguments.keySet())
            assertTrue(arguments.containsKey("expected_generation"))
            assertEquals(generation, SleepTimerCommands.readExpected(arguments))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SleepTimerCommands.readExpected(SleepTimerCommands.refreshArguments())
        }
    }

    @Test fun commandReadersRejectMissingFieldsWrongTypesAndInvalidValues() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "missing schema" to { remove("schema") },
            "long schema" to { putLong("schema", 1L) },
            "unsupported schema" to { putInt("schema", 2) },
            "missing expected" to { remove("expected_generation") },
            "boolean expected" to { putBoolean("expected_generation", false) },
            "noncanonical expected" to { putString("expected_generation", GENERATION.uppercase()) },
            "missing minutes" to { remove("minutes") },
            "long minutes" to { putLong("minutes", 2L) },
            "zero minutes" to { putInt("minutes", 0) },
            "excess minutes" to { putInt("minutes", 181) },
            "missing mode" to { remove("mode") },
            "integer mode" to { putInt("mode", 0) },
            "unknown mode" to { putString("mode", "UNKNOWN_VALUE") },
        )
        mutations.forEach { (field, mutate) ->
            assertThrows(field, IllegalArgumentException::class.java) {
                SleepTimerCommands.readArm(SleepTimerCommands.armArguments(2,
                    SleepTimerEndMode.AFTER_CURRENT_TRACK, GENERATION).apply(mutate))
            }
        }
        INVALID_GENERATIONS.forEach { generation ->
            assertThrows(IllegalArgumentException::class.java) {
                SleepTimerCommands.readExpected(SleepTimerCommands.expectedArguments(null).apply {
                    putString("expected_generation", generation)
                })
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SleepTimerCommands.readArm(activeExtras()) }
    }

    @Test fun commandBuildersRejectInvalidDurationAndGenerationBeforeDispatch() {
        listOf(Int.MIN_VALUE, 0, 181, Int.MAX_VALUE).forEach { minutes ->
            assertThrows(IllegalArgumentException::class.java) {
                SleepTimerCommands.armArguments(minutes, SleepTimerEndMode.PAUSE_IMMEDIATELY, GENERATION)
            }
        }
        INVALID_GENERATIONS.forEach { generation ->
            assertThrows(IllegalArgumentException::class.java) { SleepTimerCommands.expectedArguments(generation) }
        }
    }

    @Test fun refreshHasItsOwnSchemaOnlyContractAndDoesNotPretendToCancel() {
        val arguments = SleepTimerCommands.refreshArguments()
        assertEquals(setOf("schema"), arguments.keySet())
        SleepTimerCommands.readRefresh(arguments)
        listOf<Bundle.() -> Unit>(
            { remove("schema") }, { putString("schema", "1") }, { putInt("schema", 0) },
        ).forEach { mutate ->
            assertThrows(IllegalArgumentException::class.java) {
                SleepTimerCommands.readRefresh(SleepTimerCommands.refreshArguments().apply(mutate))
            }
        }
        assertEquals(PREFIX + "ARM", SleepTimerCommands.arm.customAction)
        assertEquals(PREFIX + "CANCEL", SleepTimerCommands.cancel.customAction)
        assertEquals(PREFIX + "RETRY_SAVE", SleepTimerCommands.retrySave.customAction)
        assertEquals(PREFIX + "REFRESH", SleepTimerCommands.refresh.customAction)
    }

    @Test fun commandsKeepTheObservedGenerationWhenASeparateSessionUpdateArrives() {
        val first = activeObservation()
        val arm = SleepTimerCommands.armArguments(3, SleepTimerEndMode.PAUSE_IMMEDIATELY, first.generationId)
        val cancel = SleepTimerCommands.expectedArguments(first.generationId)
        val replacement = first.copy(generationId = NEXT_GENERATION)
        val received = requireNotNull(SleepTimerSessionState.fromSessionExtras(replacement.toSessionExtras()))
        assertEquals(NEXT_GENERATION, received.generationId)
        assertEquals(GENERATION, SleepTimerCommands.readArm(arm).expectedGeneration)
        assertEquals(GENERATION, SleepTimerCommands.readExpected(cancel))
        assertNotEquals(received.generationId, SleepTimerCommands.readExpected(cancel))
    }

    private fun token() = SleepTimerToken(GENERATION, "private-boot", 121_000L)

    private fun activeObservation() = SleepTimerSessionState(initialized = true, generationId = GENERATION,
        active = true, minutes = 2, deadlineElapsedMs = 121_000L,
        endMode = SleepTimerEndMode.AFTER_CURRENT_TRACK, persistence = SleepTimerPersistence.SAVED)

    private fun activeExtras() = activeObservation().toSessionExtras()

    @Suppress("DEPRECATION")
    private fun Bundle.raw(key: String): Any? = get(key)

    private companion object {
        const val PREFIX = "io.github.onlinemo.meloxwatch.sleep."
        const val GENERATION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val NEXT_GENERATION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val REQUIRED_FIELDS = listOf("schema", "initialized", "active", "waiting", "persistence",
            "conflict", "summary", "notificationAllowed")
        val INVALID_GENERATIONS = listOf("", "1-1-1-1-1", GENERATION.uppercase(),
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa-extra", "invalid-generation")
    }
}
