package com.bzflipper.mc;

import java.util.Locale;

/**
 * Buckets a multiplayer disconnect reason into a reconnect policy.
 *
 * WHY match on text: Minecraft funnels every error close onto one
 * DisconnectedScreen whose reason Text is either a vanilla translation (rendered
 * to English) or ARBITRARY server text — Hypixel and most servers send literal
 * strings via {@code disconnect.genericReason = "%s"}, so there are no keys to
 * match. We therefore match on the rendered string, which covers both the
 * vanilla set and custom server wording.
 *
 * The vanilla substrings below were taken from the 1.21.11 {@code en_us.json}
 * {@code disconnect.*} / {@code multiplayer.disconnect.*} keys; the extras are
 * Hypixel's own kick wording. Anything unrecognised defaults to RECONNECT — a
 * transient network drop (timeout, connection lost, lag kick) is the common case
 * and retrying is safe.
 *
 * Deliberately conservative for a Bazaar macro: staff/ban/duplicate-login style
 * closes STOP (immediately rejoining after a staff kick screams "bot"), capacity
 * and auth closes BACKOFF (wait, then retry), and normal network drops RECONNECT.
 */
public final class DisconnectClassifier {

    private DisconnectClassifier() {}

    public enum Action { RECONNECT, BACKOFF, STOP }

    /** @param label short human tag for the log, e.g. "server full". */
    public record Verdict(Action action, String label) {}

    // STOP — permanent / punitive / needs a human. Reconnecting won't help or is unwise.
    // {matched-substring (already normalized), log label}
    private static final String[][] STOP = {
        {"banned", "banned"},                             // vanilla + Hypixel perm/temp/IP bans all contain this
        {"white-list", "not whitelisted"},               // multiplayer.disconnect.not_whitelisted
        {"whitelist", "not whitelisted"},
        {"logged in from another location", "duplicate login"}, // someone else is on the account
        {"you logged in from another", "duplicate login"},
        {"incompatible client", "version mismatch"},     // incompatible / outdated_client / outdated_server
        {"outdated", "version mismatch"},
        {"multiplayer is disabled", "account restricted"},
        {"microsoft account", "account restricted"},     // ...check your Microsoft account settings
        {"invalid session", "session invalid"},          // needs launcher/game restart, not a rejoin
        {"you are banned from playing online", "account banned"},
        {"code of conduct", "must accept code of conduct"},
        {"does not accept transfers", "transfers disabled"},
        {"profile public key", "key/clock problem"},     // expired / invalid signature for profile public key
        {"kicked by an operator", "staff kick"},         // manual staff action — don't come straight back
    };

    // BACKOFF — availability / capacity / auth / join hiccup. Retry, but wait longer.
    private static final String[][] BACKOFF = {
        {"server is full", "server full"},               // multiplayer.disconnect.server_full
        {"server full", "server full"},
        {"is currently full", "server full"},            // Hypixel "The server is currently full..."
        {"authentication servers", "auth servers down"}, // authservers_down / serversUnavailable
        {"authservers", "auth servers down"},
        {"server closed", "server restarting"},          // multiplayer.disconnect.server_shutdown
        {"server is restarting", "server restarting"},   // Hypixel restart
        {"restarting", "server restarting"},
        {"took too long to log in", "slow login"},       // slow_login
        {"already connected to this proxy", "stale session"}, // Hypixel: old session still lingering
        {"kicked whilst joining", "join hiccup"},        // Hypixel limbo bounce
        {"kicked while joining", "join hiccup"},
        {"unknown host", "host not resolving"},           // may be a transient DNS blip
        {"something went wrong", "server hiccup"},
        {"try again", "server asked to retry"},
    };

    // Everything else -> RECONNECT: timeouts, connection lost, end of stream,
    // network protocol errors, generic "Disconnected", AFK/idle kicks, spam /
    // packet-rate kicks, transfers. These are the bread-and-butter drops the
    // macro should quietly rejoin from.

    public static Verdict classify(String rawReason) {
        String r = normalize(rawReason);
        if (r.isEmpty()) return new Verdict(Action.RECONNECT, "no reason given");
        for (String[] e : STOP)    if (r.contains(e[0])) return new Verdict(Action.STOP, e[1]);
        for (String[] e : BACKOFF) if (r.contains(e[0])) return new Verdict(Action.BACKOFF, e[1]);
        return new Verdict(Action.RECONNECT, "transient");
    }

    /** Lowercase, strip §-colour codes, flatten newlines, collapse whitespace. */
    static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)§[0-9a-fk-or]", " ")
                .replace('\n', ' ')
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }
}
