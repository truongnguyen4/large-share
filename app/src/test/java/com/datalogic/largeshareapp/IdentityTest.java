package com.datalogic.largeshareapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.security.Identity;

import org.junit.Test;

import java.util.Map;

public class IdentityTest {
    private static final long NOW = 1_800_000_000_000L;

    private Identity.Verdict verify(Identity server, Identity.SignedRequest req, String method,
                                    String target, boolean replay, long now) {
        Map<String, String> h = req.headers;
        return server.verifyRequest(h.get(Identity.HDR_ID), h.get(Identity.HDR_TS), h.get(Identity.HDR_NONCE),
                h.get(Identity.HDR_SIG), method, target, replay, now);
    }

    @Test
    public void deviceWithTheFleetKeyIsAccepted() {
        Identity client = new Identity("dev-A", "fleet-secret");
        Identity server = new Identity("dev-B", "fleet-secret");
        Identity.SignedRequest req = client.signRequest("GET", "/chunk?id=5", NOW);
        assertEquals(Identity.Verdict.OK, verify(server, req, "GET", "/chunk?id=5", false, NOW));
    }

    @Test
    public void deviceWithTheWrongFleetKeyIsRejected() {
        Identity rogue = new Identity("rogue", "other-secret");
        Identity server = new Identity("dev-B", "fleet-secret");
        Identity.SignedRequest req = rogue.signRequest("GET", "/metadata", NOW);
        assertEquals(Identity.Verdict.BAD_SIGNATURE, verify(server, req, "GET", "/metadata", false, NOW));
    }

    @Test
    public void requestCannotBeRedirectedToAnotherResource() {
        Identity client = new Identity("dev-A", "fleet-secret");
        Identity server = new Identity("dev-B", "fleet-secret");
        Identity.SignedRequest req = client.signRequest("GET", "/chunk?id=5", NOW);
        assertEquals(Identity.Verdict.BAD_SIGNATURE, verify(server, req, "GET", "/chunk?id=6", false, NOW));
        assertEquals(Identity.Verdict.BAD_SIGNATURE, verify(server, req, "POST", "/chunk?id=5", false, NOW));
    }

    @Test
    public void claimingAnotherDevicesIdBreaksTheSignature() {
        Identity client = new Identity("dev-A", "fleet-secret");
        Identity server = new Identity("dev-B", "fleet-secret");
        Identity.SignedRequest req = client.signRequest("GET", "/bitset", NOW);
        Map<String, String> h = req.headers;
        Identity.Verdict v = server.verifyRequest("dev-C", h.get(Identity.HDR_TS), h.get(Identity.HDR_NONCE),
                h.get(Identity.HDR_SIG), "GET", "/bitset", false, NOW);
        assertEquals(Identity.Verdict.BAD_SIGNATURE, v);
    }

    @Test
    public void staleRequestsExpire() {
        Identity client = new Identity("dev-A", "fleet-secret");
        Identity server = new Identity("dev-B", "fleet-secret");
        Identity.SignedRequest req = client.signRequest("GET", "/bitset", NOW);
        assertEquals(Identity.Verdict.EXPIRED,
                verify(server, req, "GET", "/bitset", false, NOW + Identity.MAX_SKEW_MS + 5_000));
        assertEquals(Identity.Verdict.OK,
                verify(server, req, "GET", "/bitset", false, NOW + Identity.MAX_SKEW_MS - 5_000));
    }

    @Test
    public void stateChangingRequestsCannotBeReplayed() {
        Identity client = new Identity("dev-A", "fleet-secret");
        Identity server = new Identity("dev-B", "fleet-secret");
        Identity.SignedRequest req = client.signRequest("POST", "/progress", NOW);
        assertEquals(Identity.Verdict.OK, verify(server, req, "POST", "/progress", true, NOW));
        assertEquals(Identity.Verdict.REPLAY, verify(server, req, "POST", "/progress", true, NOW + 1000));
    }

    @Test
    public void missingHeadersAreRejected() {
        Identity server = new Identity("dev-B", "fleet-secret");
        assertEquals(Identity.Verdict.MISSING,
                server.verifyRequest(null, null, null, null, "GET", "/metadata", false, NOW));
    }

    @Test
    public void serverMustProveFleetMembershipToo() {
        Identity honest = new Identity("dev-B", "fleet-secret");
        Identity impostor = new Identity("dev-B", "wrong-secret");
        Identity client = new Identity("dev-A", "fleet-secret");
        Identity.SignedRequest req = client.signRequest("GET", "/metadata", NOW);

        String good = honest.signResponse(req.nonce, 200, 1000);
        String fake = impostor.signResponse(req.nonce, 200, 1000);
        assertTrue(client.verifyResponse(req.nonce, "dev-B", 200, 1000, good));
        assertFalse(client.verifyResponse(req.nonce, "dev-B", 200, 1000, fake));
        // bound to this request, this status and this length
        assertFalse(client.verifyResponse("other-nonce", "dev-B", 200, 1000, good));
        assertFalse(client.verifyResponse(req.nonce, "dev-B", 200, 999, good));
        assertFalse(client.verifyResponse(req.nonce, "dev-X", 200, 1000, good));
    }

    @Test
    public void manifestSignatureStopsPoisonedChunkTables() throws Exception {
        String[] hashes = {SecureManager.sha256Hex(new byte[]{1}), SecureManager.sha256Hex(new byte[]{2})};
        InformationMetadata real = new InformationMetadata("fw.zip", 8192, 4096, hashes,
                SecureManager.sha256Hex(new byte[]{9}));
        Identity root = new Identity("root", "fleet-secret");
        String json = InformationMetadata.toJsonString(real, root.signManifest(real));

        Identity leecher = new Identity("leech", "fleet-secret");
        assertTrue(leecher.verifyManifest(InformationMetadata.fromJsonString(json)));

        // a rogue peer swaps one chunk hash but keeps the real signature
        String poisoned = json.replace(hashes[1], SecureManager.sha256Hex(new byte[]{66}));
        assertFalse(leecher.verifyManifest(InformationMetadata.fromJsonString(poisoned)));

        // a rogue peer signs its own manifest with the wrong key
        Identity rogue = new Identity("rogue", "other");
        String forged = InformationMetadata.toJsonString(real, rogue.signManifest(real));
        assertFalse(leecher.verifyManifest(InformationMetadata.fromJsonString(forged)));

        // unsigned manifests are never trusted
        assertFalse(leecher.verifyManifest(
                InformationMetadata.fromJsonString(InformationMetadata.toJsonString(real, null))));
    }
}
