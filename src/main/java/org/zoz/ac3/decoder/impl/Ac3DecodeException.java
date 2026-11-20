package org.zoz.ac3.decoder.impl;

/** Signals an invalid or undecodable AC-3 syncframe (see section 7.10 of A/52). */
public class Ac3DecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Ac3DecodeException(String message) {
        super(message);
    }
}
