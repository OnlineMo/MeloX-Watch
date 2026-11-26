package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实Media3裁剪值与运行实体标识核验；未测量解码或设备输出。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class CuePlaybackClippingTest {
    @Test fun media3EndUsesTheActualSampleWindowAndSystemEndsAtTheSameWindowEof() {
        val fixture = CueByteFixture(32_003)
        val reference = fixture.reference(1, start = 0L, end = 1L)
        assertEquals(427L, requireNotNull(reference.pcmWindow).endSampleExclusive)
        assertEquals(13_333L, reference.identity.endUsExclusive)
        val media3 = cuePlaybackClipping(reference, systemBackend = false)
        val item = MediaItem.Builder().setMediaId("fixture").setUri("meloxcue://resource/fixture")
            .setClippingConfiguration(media3).build()
        assertEquals(0L, item.clippingConfiguration.startPositionUs)
        assertEquals(13_342L, item.clippingConfiguration.endPositionUs)
        assertTrue(item.clippingConfiguration.startsAtKeyFrame)
        assertFalse(item.clippingConfiguration.relativeToDefaultPosition)
        assertEquals(C.TIME_END_OF_SOURCE, cuePlaybackClipping(reference, systemBackend = true).endPositionUs)
        assertEquals(MediaItem.ClippingConfiguration.UNSET, cuePlaybackClipping(null, systemBackend = false))
    }

    @Test fun oneQueueInstanceCannotRetainTheOldRuntimeResourceAfterCueBoundaryOrRevisionChanges() {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val old = fixture.entry(1)
        val resource = registry.registerCue(old)
        try {
            val reference = requireNotNull(old.cueReference)
            val changedBoundary = fixture.reference(1, start = 0L, end = 77L)
            val changedRevision = reference.copy(identity = reference.identity.copy(groupRevision = 2L))
            for (changed in listOf(changedBoundary, changedRevision)) {
                val replacement = old.copy(cueReference = changed)
                assertEquals(old.queueEntryId, replacement.queueEntryId)
                assertEquals(old.location, replacement.location)
                assertEquals(QueuePlaybackIssue.SOURCE_CHANGED, assertThrows(QueuePlaybackException::class.java) {
                    resource.retain(replacement)
                }.failure.issue)
            }
            assertNotEquals(cuePlaybackClipping(reference, false), cuePlaybackClipping(changedBoundary, false))
            val retained = resource.retain(old)
            retained.close()
            resource.checkCurrent()
        } finally { resource.close() }
    }

    @Test fun portablePendingCueIsRejectedBeforeAClippingConfigurationCanNameABareFile() {
        val reference = CueByteFixture().reference(2).portable()
        for (system in listOf(true, false)) assertEquals(QueuePlaybackIssue.SOURCE_CHANGED,
            assertThrows(QueuePlaybackException::class.java) { cuePlaybackClipping(reference, system) }.failure.issue)
    }
}
