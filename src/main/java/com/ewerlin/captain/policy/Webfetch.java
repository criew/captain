package com.ewerlin.captain.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ewerlin.captain.opencode.Rule;

/**
 * webfetch-Allowlist ({@code CAPTAIN_WEBFETCH_ALLOW}): Normalisierung und Muster.
 *
 * <p>Der Admin gibt kommagetrennte URL-Präfixe an, z. B. {@code http://text-example.org} oder
 * {@code https://docs.example.org/handbuch}. Leer = webfetch komplett aus.
 *
 * <p>Wie opencode (an 2.0.20 ermittelt, mit 2.2.0 getestet) webfetch prüft (aus dem Binary ermittelt, per
 * {@code WebfetchIntegrationTest} belegt):
 * <ul>
 * <li>Ressource ist die <b>rohe URL</b>, wie das Modell sie übergibt (keine Normalisierung; vorher
 * nur {@code new URL()} + Schema http/https).</li>
 * <li>Muster: {@code *} = beliebig viele Zeichen (auch {@code /}), {@code ?} = <b>genau ein
 * beliebiges Zeichen</b>, sonst wörtlich und mit Groß-/Kleinschreibung; {@code \} im Wert zählt als
 * {@code /}. Regeln: letzte passende gewinnt.</li>
 * <li>Weiterleitungen folgt opencode selbst, <b>ohne</b> erneute Prüfung. Das fängt der
 * Egress-Filter in {@code infra/opencode/egress.mjs} auf Host-Ebene ab.</li>
 * </ul>
 *
 * <p>Daraus folgt die Normalisierung: Ein Eintrag wird zu genau zwei Mustern, {@code <präfix>} und
 * {@code <präfix>/*}. Das {@code /} direkt hinter Host bzw. Pfad verhindert Präfix-Tricks
 * ({@code http://text-example.org.evil.com}, {@code http://text-example.org@evil.com},
 * {@code …:8080}); {@code ?} und {@code *} kommen in Einträgen nicht vor ({@code ?} wäre ein
 * Platzhalter für ein beliebiges Zeichen). Großgeschriebene Hosts, abweichende Ports oder ein anderes
 * Schema passen nicht und werden abgelehnt (sicherer Fehler). Pfadsegmente {@code ..} (auch
 * prozentkodiert) und Tab/Zeilenumbruch (entfernt der URL-Parser) werden zusätzlich verboten, sonst
 * käme man aus einem Pfad-Präfix heraus.
 *
 * <p>Dieselbe Logik steckt in {@code infra/opencode/start.mjs} (Policies als harte Obergrenze);
 * gemeinsame Testfälle: {@code src/test/resources/data/webfetch_allow.json}.
 */
public final class Webfetch {

    public static final String ENV = "CAPTAIN_WEBFETCH_ALLOW";

    private static final Pattern ENTRY = Pattern.compile(
            "(?<scheme>[A-Za-z]+)://(?<host>\\[[0-9A-Fa-f:.]+\\]|[^/:\\[\\]]+)(?::(?<port>[0-9]{1,5}))?(?<path>/.*)?",
            Pattern.DOTALL);
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Map<String, Integer> DEFAULT_PORT = Map.of("http", 80, "https", 443);
    // Zeichen, die in einem Eintrag nie vorkommen dürfen: Platzhalter der Muster (*, ?),
    // Query/Fragment, Backslash (opencode liest ihn als /), Userinfo, Leer- und Steuerzeichen.
    private static final Pattern FORBIDDEN = Pattern.compile("[*?#\\\\@\\s\\x00-\\x1f\\x7f]");

    /**
     * Nach den Freigaben: kein {@code ..}-Segment (auch kodiert), keine Zeichen, die der URL-Parser
     * still entfernt (Tab, LF, CR – daraus würde z. B. {@code /.\t./} ein {@code /../}). {@code ?}
     * ist hier gewollt der Ein-Zeichen-Platzhalter.
     */
    public static final List<String> DENY_PATTERNS = List.of(
            "*/..", "*/..?*",
            "*/%2e*", "*/%2E*", "*/.%2e*", "*/.%2E*",
            "*\t*", "*\n*", "*\r*");

    private Webfetch() {
    }

    private static String host(String raw) {
        String host = raw.toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            return host; // IPv6-Literal, Zeichen schon durch ENTRY begrenzt
        }
        boolean ok = host.length() <= 253;
        for (String label : host.split("\\.", -1)) {
            ok &= LABEL.matcher(label).matches();
        }
        if (!ok) {
            throw new IllegalArgumentException("ungültiger Hostname (nur a-z, 0-9, '-', Punkte; IDN als Punycode)");
        }
        return host;
    }

    /** Ein Eintrag → kanonisches Präfix {@code schema://host[:port][/pfad]}. */
    public static String normalizeEntry(String entry) {
        if (!entry.chars().allMatch(c -> c < 128)) {
            throw new IllegalArgumentException("nur ASCII (IDN als Punycode xn--…)");
        }
        Matcher bad = FORBIDDEN.matcher(entry);
        if (bad.find()) {
            throw new IllegalArgumentException(
                    "Zeichen " + quote(bad.group()) + " nicht erlaubt (keine Platzhalter, Query, Userinfo)");
        }
        Matcher m = ENTRY.matcher(entry);
        if (!m.matches()) {
            throw new IllegalArgumentException("Format: http(s)://host[:port][/pfad]");
        }
        String scheme = m.group("scheme").toLowerCase(Locale.ROOT);
        if (!DEFAULT_PORT.containsKey(scheme)) {
            throw new IllegalArgumentException("nur http:// oder https://");
        }
        String host = host(m.group("host"));
        String port = "";
        if (m.group("port") != null) {
            int number = Integer.parseInt(m.group("port"));
            if (number <= 0 || number >= 65536) {
                throw new IllegalArgumentException("ungültiger Port");
            }
            if (number != DEFAULT_PORT.get(scheme)) {
                port = ":" + number;
            }
        }
        String path = rstrip(m.group("path") == null ? "" : m.group("path"), '/');
        String[] segments = path.split("/", -1);
        boolean badSegment = false;
        for (int i = 1; i < segments.length; i++) {
            String s = segments[i];
            badSegment |= s.equals(".") || s.equals("..") || s.toLowerCase(Locale.ROOT).contains("%2e");
        }
        if (badSegment || path.contains("//")) {
            throw new IllegalArgumentException("Pfad ohne '.', '..', '%2e' und leere Segmente");
        }
        return scheme + "://" + host + port + path;
    }

    /**
     * Kommagetrennte Einträge → kanonische Präfixe (dedupliziert, Reihenfolge bleibt).
     *
     * <p>Wirft {@link IllegalArgumentException} mit dem fehlerhaften Eintrag – lieber nicht starten
     * als still etwas anderes freigeben.
     */
    public static List<String> parse(String value) {
        List<String> out = new ArrayList<>();
        for (String raw : (value == null ? "" : value).split(",", -1)) {
            String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
            String prefix;
            try {
                prefix = normalizeEntry(entry);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(ENV + ": Eintrag " + quote(entry) + " ungültig: " + e.getMessage());
            }
            if (!out.contains(prefix)) {
                out.add(prefix);
            }
        }
        return List.copyOf(out);
    }

    /** Muster für Regeln/Policies: je Präfix {@code p} und {@code p/*}. */
    public static List<String> allowPatterns(List<String> prefixes) {
        List<String> out = new ArrayList<>();
        for (String prefix : prefixes) {
            out.add(prefix);
            out.add(prefix + "/*");
        }
        return out;
    }

    /** Session-Regeln, die <i>nach</i> {@code webfetch * deny} kommen (letzte gewinnt). */
    public static List<Rule> sessionRules(List<String> prefixes) {
        List<Rule> rules = new ArrayList<>();
        if (prefixes == null || prefixes.isEmpty()) {
            return rules;
        }
        for (String p : allowPatterns(prefixes)) {
            rules.add(Rule.allow("webfetch", p));
        }
        for (String p : DENY_PATTERNS) {
            rules.add(Rule.deny("webfetch", p));
        }
        return rules;
    }

    static String rstrip(String text, char c) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == c) {
            end--;
        }
        return text.substring(0, end);
    }

    static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }
}
