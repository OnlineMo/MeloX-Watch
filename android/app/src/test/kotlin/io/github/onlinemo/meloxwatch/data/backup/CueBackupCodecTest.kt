package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CueBackupCodecTest {
    @Test fun logicalRowsPreserveCueHintsButRejectNativeAccessAndMismatchedTrackIdentity() {
        val reference = CueByteFixture().reference(2)
        val original = BackupTestFixtures.track(reference.trackKey.trackId, 1L)
        val pending = BackupRecord(original.kind, original.key, original.values + ("cueReference" to portableCueCell(CueReferenceCodec.encode(reference))))
        val parsed = BackupJsonCodec.readRecord(BackupJsonCodec.record(pending))
        assertEquals(reference.portable(), parsed.portableCueReference())
        val text = String(BackupJsonCodec.record(pending), Charsets.UTF_8)
        assertFalse(text.contains("actualDocument"));
        assertFalse(text.contains("grantUri"));
        assertFalse(text.contains("content://"))
        assertThrows(BackupException::class.java) {
            BackupJsonCodec.record(BackupRecord(original.kind, original.key, original.values + ("cueReference" to BackupCell.Text(CueReferenceCodec.encode(reference)))))
        }
        assertThrows(BackupException::class.java) { BackupJsonCodec.record(BackupRecord(pending.kind, "another-track", pending.values)) }
        assertEquals(null, BackupJsonCodec.readRecord(BackupJsonCodec.record(BackupTestFixtures.track("legacy-track", 2L))).portableCueReference())
    }
    @Test fun privateJournalHintRoundTripRetainsPortableCueWhileOldPayloadsRemainReadable() {
        val reference = CueByteFixture().reference(2).portable()
        val hint = syntheticBackupRelinkHint("a".repeat(64), reference.trackKey.trackId, reference)
        val (record, restored) = BackupRelinkHintCodec.decode(BackupRelinkHintCodec.encode(hint.record, hint.cueReference))
        assertEquals(hint.record.key, record.key);
        assertEquals(reference, restored)
        assertEquals(null, BackupRelinkHintCodec.decode(BackupJsonCodec.record(hint.record)).second)
        assertThrows(BackupException::class.java) { BackupRelinkHintCodec.decode(byteArrayOf(67, 85, 69, 49, 0)) }
    }
}
