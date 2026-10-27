package com.ewerlin.captain;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.ewerlin.captain.api.ApiServer;
import com.ewerlin.captain.bot.Bot;
import com.ewerlin.captain.bot.SessionStore;
import com.ewerlin.captain.config.CaptainConfig;
import com.ewerlin.captain.config.ConfigLoader;
import com.ewerlin.captain.mattermost.MattermostClient;
import com.ewerlin.captain.opencode.OpencodeClient;

/** Verdrahtet Konfiguration, Clients und Bot. */
@Configuration(proxyBeanMethods = false)
public class CaptainConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CaptainConfiguration.class);

    /** Aus Springs {@link Environment}: Umgebungsvariablen, Systemeigenschaften, Kommandozeile. */
    @Bean
    CaptainConfig captainConfig(Environment env) {
        CaptainConfig cfg = ConfigLoader.load(env::getProperty);
        log.info("Mattermost {}, opencode {}, Modell {}, Session-Verzeichnisse {}, Daten {}, webfetch {}, "
                        + "geteiltes Verzeichnis {}",
                cfg.mmUrl(), cfg.opencodeUrl(), cfg.opencodeModel() == null ? "(Standard)" : cfg.opencodeModel(),
                cfg.sessionsDir(), cfg.dataDir(),
                cfg.webfetchAllow().isEmpty() ? "aus" : String.join(", ", cfg.webfetchAllow()),
                cfg.shared() ? cfg.sharedDir() + " → /shared (nur lesen)" : "aus");
        return cfg;
    }

    @Bean
    MattermostClient mattermostClient(CaptainConfig cfg) {
        return new MattermostClient(cfg.mmUrl(), cfg.mmBotToken());
    }

    /** Ein Client pro Prozess: sein SSE-Thread bedient alle Sessions. */
    @Bean
    OpencodeClient opencodeClient(CaptainConfig cfg) {
        return new OpencodeClient(cfg.opencodeUrl(), cfg.opencodePassword());
    }

    @Bean
    SessionStore sessionStore(CaptainConfig cfg) {
        return new SessionStore(Path.of(cfg.dataDir(), "sessions.json"));
    }

    @Bean
    Bot bot(CaptainConfig cfg, MattermostClient mm, OpencodeClient oc, SessionStore store) {
        return new Bot(cfg, mm, oc, store);
    }

    /** REST-Eingabekanal nur mit {@code CAPTAIN_API_TOKEN}. */
    @Bean
    BotLifecycle botLifecycle(CaptainConfig cfg, Bot bot) throws IOException {
        ApiServer api = cfg.apiToken() == null ? null
                : new ApiServer(bot, cfg.apiToken(), new InetSocketAddress(ApiServer.PORT));
        if (api == null) {
            log.info("REST-Eingabekanal aus (CAPTAIN_API_TOKEN leer)");
        }
        return new BotLifecycle(bot, api);
    }
}
