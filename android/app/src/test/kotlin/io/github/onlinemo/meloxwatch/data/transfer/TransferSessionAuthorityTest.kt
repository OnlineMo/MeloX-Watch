package io.github.onlinemo.meloxwatch.data.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferSessionAuthorityTest {
    @Test fun defaultOffHasNoCodeOrAuthorization() {
        val authority = TransferSessionAuthority()
        assertFalse(authority.state.value.active)
        assertNull(authority.pairingCodeForLocalUi())
        expect(TransferIssue.CLOSED) { authority.currentOpening() }
    }

    @Test fun correctCodeCreatesOnlyPendingAndExactLocalConfirmationGrantsTheSubset() {
        val authority = TransferSessionAuthority()
        val opening = authority.begin(TransferTestFixtures.fingerprint())
        val peer = TransferTestFixtures.fingerprint()
        val requested = setOf(TransferCapability.UPLOAD_AUDIO, TransferCapability.READ_LIBRARY)
        authority.pair(peer, TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId, checkNotNull(authority.pairingCodeForLocalUi()), requested))
        expect(TransferIssue.NOT_APPROVED) { authority.authenticate(peer, opening.target, opening.sessionId) }
        expect(TransferIssue.WRONG_TARGET) { authority.approveForLocalUi(TransferTestFixtures.fingerprint(), requested) }
        expect(TransferIssue.PERMISSION_DENIED) { authority.approveForLocalUi(peer, setOf(TransferCapability.WRITE_PLAYLISTS)) }
        authority.approveForLocalUi(peer, setOf(TransferCapability.UPLOAD_AUDIO))
        val authentication = authority.authenticate(peer, opening.target, opening.sessionId)
        val scope = TransferScope(opening.target, opening.sessionId, authentication.bearer)
        authority.authorize(peer, scope, TransferCapability.UPLOAD_AUDIO).checkActive()
        expect(TransferIssue.PERMISSION_DENIED) { authority.authorize(peer, scope, TransferCapability.READ_LIBRARY) }
        assertNull(authority.pairingCodeForLocalUi())
    }

    @Test fun failuresAreGlobalAcrossPeersAndTheThirdGuessLocksTheCode() {
        val authority = TransferSessionAuthority()
        val opening = authority.begin(TransferTestFixtures.fingerprint())
        val actual = checkNotNull(authority.pairingCodeForLocalUi())
        val wrong = if (actual == "00000000") "00000001" else "00000000"
        repeat(3) { index ->
            expect(if (index == 2) TransferIssue.PAIRING_LOCKED else TransferIssue.PAIRING_FAILED) {
                authority.pair(TransferTestFixtures.fingerprint(), TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId, wrong, setOf(TransferCapability.UPLOAD_AUDIO)))
            }
        }
        assertNull(authority.pairingCodeForLocalUi())
        expect(TransferIssue.PAIRING_LOCKED) { authority.pair(TransferTestFixtures.fingerprint(), TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId, actual, setOf(TransferCapability.UPLOAD_AUDIO))) }
    }

    @Test fun pendingPeerAndCodeExpireAtTheSameNinetySecondBoundary() {
        val clock = TransferTestFixtures.Clock()
        val authority = TransferSessionAuthority(clock)
        val opening = authority.begin(TransferTestFixtures.fingerprint())
        val peer = TransferTestFixtures.fingerprint()
        authority.pair(peer, TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId, checkNotNull(authority.pairingCodeForLocalUi()), setOf(TransferCapability.UPLOAD_AUDIO)))
        clock.now = TransferLimits.PAIRING_MS
        assertNull(authority.pairingCodeForLocalUi())
        expect(TransferIssue.EXPIRED) { authority.approveForLocalUi(peer, setOf(TransferCapability.UPLOAD_AUDIO)) }
    }

    @Test fun onlyOnePendingPeerIsRetained() {
        val authority = TransferSessionAuthority()
        val opening = authority.begin(TransferTestFixtures.fingerprint())
        val code = checkNotNull(authority.pairingCodeForLocalUi())
        val first = TransferTestFixtures.fingerprint()
        authority.pair(first, TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId, code, setOf(TransferCapability.UPLOAD_AUDIO)))
        expect(TransferIssue.BUSY) { authority.pair(TransferTestFixtures.fingerprint(), TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId, code, setOf(TransferCapability.UPLOAD_AUDIO))) }
        assertTrue(authority.state.value.pending?.fingerprint == first)
    }

    @Test fun bearerIsBoundToTlsPeerTargetAndSession() {
        val auth = TransferTestFixtures.authorized()
        expect(TransferIssue.NOT_APPROVED) { auth.authority.authorize(TransferTestFixtures.fingerprint(), auth.scope, TransferCapability.UPLOAD_AUDIO) }
        expect(TransferIssue.WRONG_TARGET) { auth.authority.authorize(auth.peer, TransferScope(TransferTestFixtures.fingerprint(), auth.scope.sessionId, auth.scope.bearer), TransferCapability.UPLOAD_AUDIO) }
        expect(TransferIssue.WRONG_TARGET) { auth.authority.authorize(auth.peer, TransferScope(auth.scope.target, TransferTestFixtures.id(), auth.scope.bearer), TransferCapability.UPLOAD_AUDIO) }
        expect(TransferIssue.UNAUTHENTICATED) { auth.authority.authorize(auth.peer, TransferScope(auth.scope.target, auth.scope.sessionId, TransferBearer.generate(java.security.SecureRandom())), TransferCapability.UPLOAD_AUDIO) }
    }

    @Test fun renewingAnApprovedGrantKeepsAnUploadBindingButCannotRestoreRevocation() {
        val clock = TransferTestFixtures.Clock()
        val auth = TransferTestFixtures.authorized(clock = clock)
        val first = auth.permit()
        clock.now = TransferLimits.TOKEN_MS
        expect(TransferIssue.EXPIRED) { first.checkActive() }
        val renewed = auth.authority.authenticate(auth.peer, auth.opening.target, auth.opening.sessionId)
        val scope = TransferScope(auth.opening.target, auth.opening.sessionId, renewed.bearer)
        val permit = auth.authority.authorize(auth.peer, scope, TransferCapability.UPLOAD_AUDIO)
        assertTrue(first.sameGrant(permit))
        auth.authority.revokeForLocalUi(auth.peer)
        expect(TransferIssue.NOT_APPROVED) { permit.checkActive() }
        expect(TransferIssue.NOT_APPROVED) { auth.authority.authenticate(auth.peer, auth.opening.target, auth.opening.sessionId) }
    }

    @Test fun sessionExpiryStopsPermitsAndExtensionRequiresTheStillActiveLocalSession() {
        val clock = TransferTestFixtures.Clock()
        val auth = TransferTestFixtures.authorized(clock = clock)
        clock.now = TransferLimits.SESSION_MS
        expect(TransferIssue.EXPIRED) { auth.permit().checkActive() }
        expect(TransferIssue.EXPIRED) { auth.authority.extendForLocalUi() }
    }

    @Test fun stopInvalidatesOldGenerationEvenWhenTheTargetKeyIsReused() {
        val auth = TransferTestFixtures.authorized()
        val permit = auth.permit()
        auth.authority.stop()
        expect(TransferIssue.CLOSED) { permit.checkActive() }
        auth.authority.begin(auth.opening.target)
        expect(TransferIssue.NOT_APPROVED) { permit.checkActive() }
        expect(TransferIssue.WRONG_TARGET) { auth.authority.authorize(auth.peer, auth.scope, TransferCapability.UPLOAD_AUDIO) }
    }

    @Test fun tokenRotationRetainsAtMostTwoLiveTokens() {
        val auth = TransferTestFixtures.authorized()
        val first = auth.permit()
        val second = auth.authority.authenticate(auth.peer, auth.opening.target, auth.opening.sessionId)
        val secondPermit = auth.authority.authorize(auth.peer, TransferScope(auth.opening.target, auth.opening.sessionId, second.bearer), TransferCapability.UPLOAD_AUDIO)
        first.checkActive()
        auth.authority.authenticate(auth.peer, auth.opening.target, auth.opening.sessionId)
        expect(TransferIssue.UNAUTHENTICATED) { first.checkActive() }
        secondPermit.checkActive()
    }

    @Test fun mutatingObservedOrCallerSetsCannotExpandLocalApproval() {
        val authority = TransferSessionAuthority()
        val opening = authority.begin(TransferTestFixtures.fingerprint())
        val peer = TransferTestFixtures.fingerprint()
        val requested = mutableSetOf(TransferCapability.UPLOAD_AUDIO)
        val request = TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId, checkNotNull(authority.pairingCodeForLocalUi()), requested)
        requested.add(TransferCapability.READ_LIBRARY)
        authority.pair(peer, request)
        val pending = checkNotNull(authority.state.value.pending)
        try { (pending.requested as MutableSet<TransferCapability>).add(TransferCapability.WRITE_PLAYLISTS)
            fail("Expected immutable pending permissions") }
        catch (_: UnsupportedOperationException) { }
        expect(TransferIssue.PERMISSION_DENIED) { authority.approveForLocalUi(peer, setOf(TransferCapability.READ_LIBRARY)) }
        val selected = mutableSetOf(TransferCapability.UPLOAD_AUDIO)
        authority.approveForLocalUi(peer, selected)
        selected.add(TransferCapability.READ_LIBRARY)
        val authentication = authority.authenticate(peer, opening.target, opening.sessionId)
        val scope = TransferScope(opening.target, opening.sessionId, authentication.bearer)
        expect(TransferIssue.PERMISSION_DENIED) { authority.authorize(peer, scope, TransferCapability.READ_LIBRARY) }
        try { (authority.state.value.approvedCapabilities as MutableSet<TransferCapability>).clear()
            fail("Expected immutable approved permissions") }
        catch (_: UnsupportedOperationException) { }
        authority.authorize(peer, scope, TransferCapability.UPLOAD_AUDIO).checkActive()
    }

    private fun expect(issue: TransferIssue, action: () -> Unit) {
        try { action()
            fail("Expected a transfer rejection") }
        catch (error: TransferException) { assertTrue(error.issue == issue) }
    }
}
