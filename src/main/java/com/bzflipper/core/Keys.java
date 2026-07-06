package com.bzflipper.core;

import java.util.Locale;

/**
 * Canonical item keys. Grid names, API display names, and config names can
 * differ in case/punctuation ("Jacob's Ticket" vs "jacobs ticket"); comparing
 * raw strings caused the macro to not recognize its own orders and open
 * duplicates. Everything item-keyed goes through {@link #norm}.
 */
public final class Keys {

    private Keys() {}

    /** Lowercase, alphanumerics only: "Jacob's Ticket" -> "jacobsticket". */
    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
