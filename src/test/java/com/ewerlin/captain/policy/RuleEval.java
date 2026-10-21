package com.ewerlin.captain.policy;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ewerlin.captain.opencode.Rule;

/**
 * Regelauswertung wie opencode (an 2.0.20 aus dem Binary ermittelt, mit 2.1.2 getestet): {@code wildcard} = Funktion {@code JD}
 * ({@code \} im Wert → {@code /}, {@code *} → {@code .*}, {@code ?} → {@code .}, Regex mit Flag
 * {@code s}, Groß-/Kleinschreibung zählt), Regeln: letzte passende gewinnt, ohne Treffer {@code ask};
 * ein Tool ist unsichtbar, wenn die letzte Regel seiner Aktion {@code resource: "*"} + {@code deny}
 * ist. Dass opencode wirklich so entscheidet, belegen die Integrationstests.
 */
public final class RuleEval {

    private static final Pattern SPECIAL = Pattern.compile("[.+^${}()|\\[\\]\\\\]");

    private RuleEval() {
    }

    public static boolean wildcard(String value, String pattern) {
        String v = value.replace("\\", "/");
        Matcher m = SPECIAL.matcher(pattern.replace("\\", "/"));
        StringBuilder rx = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(rx, Matcher.quoteReplacement("\\" + m.group()));
        }
        m.appendTail(rx);
        String regex = rx.toString().replace("*", ".*").replace("?", ".");
        if (regex.endsWith(" .*")) {
            regex = regex.substring(0, regex.length() - 3) + "( .*)?";
        }
        return Pattern.compile("^" + regex + "$", Pattern.DOTALL).matcher(v).matches();
    }

    public static String evaluate(List<Rule> rules, String action, String resource) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule r = rules.get(i);
            if (wildcard(action, r.action()) && wildcard(resource, r.resource())) {
                return r.effect();
            }
        }
        return "ask";
    }

    public static boolean visible(List<Rule> rules, String action) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule r = rules.get(i);
            if (wildcard(action, r.action())) {
                return !(r.resource().equals("*") && r.effect().equals("deny"));
            }
        }
        return true;
    }
}
