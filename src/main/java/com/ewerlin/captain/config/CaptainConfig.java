package com.ewerlin.captain.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.ewerlin.captain.policy.SharedDir;

/**
 * Konfiguration des Bots (siehe {@link ConfigLoader}).
 *
 * @param sessionsDir   Session-Verzeichnisse (Sicht von opencode)
 * @param systemPrompt  Persona, pro Session als Systemanweisung
 * @param allowedUsers  leer = alle erlaubt
 * @param maxAttachmentMb 0 = keine Grenze
 * @param historyMaxPosts 0 = keine Vorgeschichte
 * @param webfetchAllow kanonische URL-Präfixe der webfetch-Allowlist; leer = webfetch aus
 * @param sharedDir     geteiltes Verzeichnis (Host-Pfad, nur zur Info); gesetzt = opencode liest es
 *                      unter {@link SharedDir#MOUNT}, {@code null} = aus
 */
public record CaptainConfig(
        String mmUrl,
        String mmBotToken,
        String opencodeUrl,
        String opencodePassword,
        String opencodeModel,
        String opencodeVariant,
        String sessionsDir,
        String dataDir,
        String systemPrompt,
        Set<String> allowedUsers,
        Duration catchupMaxAge,
        double maxAttachmentMb,
        int historyMaxPosts,
        int historyMaxChars,
        List<String> webfetchAllow,
        String sharedDir) {

    public CaptainConfig {
        allowedUsers = Set.copyOf(allowedUsers);
        webfetchAllow = List.copyOf(webfetchAllow);
    }

    public boolean shared() {
        return sharedDir != null;
    }

    /** Größte Anhangsgröße in Bytes; {@code null} = keine Grenze. */
    public Long maxAttachmentBytes() {
        return maxAttachmentMb != 0 ? (long) (maxAttachmentMb * 1024 * 1024) : null;
    }

    public boolean isAllowed(String username) {
        return allowedUsers.isEmpty() || allowedUsers.contains(username.toLowerCase(Locale.ROOT));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .mmUrl(mmUrl).mmBotToken(mmBotToken).opencodeUrl(opencodeUrl)
                .opencodePassword(opencodePassword).opencodeModel(opencodeModel).opencodeVariant(opencodeVariant)
                .sessionsDir(sessionsDir).dataDir(dataDir).systemPrompt(systemPrompt).allowedUsers(allowedUsers)
                .catchupMaxAge(catchupMaxAge).maxAttachmentMb(maxAttachmentMb)
                .historyMaxPosts(historyMaxPosts).historyMaxChars(historyMaxChars)
                .webfetchAllow(webfetchAllow).sharedDir(sharedDir);
    }

    /** Baut eine Konfiguration mit den Standardwerten aus {@link ConfigLoader}. */
    public static final class Builder {
        private String mmUrl;
        private String mmBotToken;
        private String opencodeUrl;
        private String opencodePassword;
        private String opencodeModel;
        private String opencodeVariant;
        private String sessionsDir = ConfigLoader.DEFAULTS.get("SESSIONS_DIR");
        private String dataDir = ConfigLoader.DEFAULTS.get("DATA_DIR");
        private String systemPrompt = ConfigLoader.DEFAULT_SYSTEM_PROMPT;
        private Set<String> allowedUsers = Set.of();
        private Duration catchupMaxAge = Duration.ofHours(24);
        private double maxAttachmentMb = 20.0;
        private int historyMaxPosts = 50;
        private int historyMaxChars = 8000;
        private List<String> webfetchAllow = List.of();
        private String sharedDir;

        public Builder mmUrl(String v) { mmUrl = v; return this; }
        public Builder mmBotToken(String v) { mmBotToken = v; return this; }
        public Builder opencodeUrl(String v) { opencodeUrl = v; return this; }
        public Builder opencodePassword(String v) { opencodePassword = v; return this; }
        public Builder opencodeModel(String v) { opencodeModel = v; return this; }
        public Builder opencodeVariant(String v) { opencodeVariant = v; return this; }
        public Builder sessionsDir(String v) { sessionsDir = v; return this; }
        public Builder dataDir(String v) { dataDir = v; return this; }
        public Builder systemPrompt(String v) { systemPrompt = v; return this; }
        public Builder allowedUsers(Set<String> v) { allowedUsers = v; return this; }
        public Builder catchupMaxAge(Duration v) { catchupMaxAge = v; return this; }
        public Builder maxAttachmentMb(double v) { maxAttachmentMb = v; return this; }
        public Builder historyMaxPosts(int v) { historyMaxPosts = v; return this; }
        public Builder historyMaxChars(int v) { historyMaxChars = v; return this; }
        public Builder webfetchAllow(List<String> v) { webfetchAllow = v; return this; }
        public Builder sharedDir(String v) { sharedDir = v; return this; }

        public CaptainConfig build() {
            return new CaptainConfig(mmUrl, mmBotToken, opencodeUrl, opencodePassword, opencodeModel,
                    opencodeVariant, sessionsDir, dataDir, systemPrompt, allowedUsers, catchupMaxAge,
                    maxAttachmentMb, historyMaxPosts, historyMaxChars, webfetchAllow, sharedDir);
        }
    }
}
