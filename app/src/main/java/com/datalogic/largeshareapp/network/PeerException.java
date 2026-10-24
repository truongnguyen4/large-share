package com.datalogic.largeshareapp.network;

/** A failed exchange with a peer, classified so the caller can react proportionately. */
public class PeerException extends Exception {
    public enum Kind {
        /** Connection refused / reset / timed out: the peer or the network is unreachable. */
        NETWORK,
        /** The peer is healthy but at capacity (HTTP 503): retry later, do not penalise. */
        BUSY,
        /** The peer rejected our proof of identity (HTTP 401/403). */
        UNAUTHORIZED,
        /** The peer could not prove it belongs to the fleet / is who it claims to be. */
        IDENTITY,
        /** The peer does not (yet) have the requested item. */
        NOT_AVAILABLE,
        /** The peer answered with something malformed or inconsistent. */
        PROTOCOL,
        /** The address turned out to be this very device (learned through peer exchange). */
        SELF
    }

    public final Kind kind;

    public PeerException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public PeerException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
}
