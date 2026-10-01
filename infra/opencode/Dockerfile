# opencode im Server-Modus (headless) fuer Captain (Test-Setup und Betrieb).
#
# Bewusst ohne Paketmanager der Distribution (kein apt-get): Hinter Firmen-
# Proxys sind Debian/Ubuntu/Alpine-Spiegel oft gesperrt. Gebraucht werden nur
# Docker Hub, npm (opencode) und GitHub-Releases (ripgrep).

# ripgrep (fuer die Such-Tools glob/grep) als Release-Binary mit Pruefsumme.
# Ohne rg wuerde opencode es zur Laufzeit von GitHub nachladen.
FROM curlimages/curl:8.11.1 AS ripgrep
ARG RG_VERSION=15.1.0
ARG TARGETARCH
USER root
RUN set -e; \
    case "${TARGETARCH:-amd64}" in \
      amd64) T=x86_64-unknown-linux-musl;  S=1c9297be4a084eea7ecaedf93eb03d058d6faae29bbc57ecdaf5063921491599 ;; \
      arm64) T=aarch64-unknown-linux-gnu;  S=2b661c6ef508e902f388e9098d9c4c5aca72c87b55922d94abdba830b4dc885e ;; \
      *) echo "Architektur ${TARGETARCH} nicht unterstuetzt" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/rg.tgz "https://github.com/BurntSushi/ripgrep/releases/download/${RG_VERSION}/ripgrep-${RG_VERSION}-${T}.tar.gz"; \
    echo "${S}  /tmp/rg.tgz" | sha256sum -c -; \
    tar -xzf /tmp/rg.tgz -C /tmp; \
    cp "/tmp/ripgrep-${RG_VERSION}-${T}/rg" /rg; \
    chmod 755 /rg

FROM node:22-bookworm-slim

# Version fest pinnen (siehe README.md, Abschnitt "Version").
ARG OPENCODE_VERSION=2.0.20

COPY --from=ripgrep /rg /usr/local/bin/rg

RUN npm install -g --no-fund --no-audit "@opencode/cli@${OPENCODE_VERSION}" \
 && opencode --version

# --- Sicherheitsbasis (fest im Image) ---------------------------------------
# Globale Config: "* * deny" + experimental.policies (harte Obergrenze).
COPY config/base.jsonc /root/.config/opencode/opencode.jsonc

# Keine Projekt-Config aus dem Session-Verzeichnis (und darueber): sonst laedt
# opencode dort opencode.json(c), .opencode/ (inkl. plugins/*.ts als JS im
# Server!), .claude/, .agents/ und AGENTS.md – das Modell darf dort schreiben.
# 2.0.20 liest OPENCODE_CONFIG_PROJECT_DISABLE ?? OPENCODE_DISABLE_PROJECT_CONFIG.
ENV OPENCODE_DISABLE_PROJECT_CONFIG=1

# Start ueber start.mjs: bereinigt die Admin-Config per Allowlist (keine
# Plugins, keine lokalen MCP-Server, keine Agents/Commands/Skills/
# Instructions/References/Shell-Einstellungen – opencode fuehrt diese
# Schluessel aus jeder Quelle zusammen, die Basis koennte sie nicht
# ueberschreiben), reicht nur benoetigte Umgebungsvariablen durch und setzt
# fest: OPENCODE_CONFIG (bereinigte Kopie), OPENCODE_CONFIG_CONTENT als letzte
# Quelle (kein Teilen, keine Updates, keine Websuche/LSP/Formatter/Snapshots),
# Projekt-Config aus. Die .env kann daran nichts aendern.
COPY start.mjs /opt/captain/start.mjs

# --- Admin-Config (editierbar, im Betrieb vom Host eingebunden) -------------
# /etc/captain/opencode.jsonc: Provider, Modelle, Limits, MCP-Server, Freigaben
# (start.mjs prueft sie; opencode liest die Kopie /run/captain/opencode.json).
# /etc/captain/AGENTS.md: globale Stil-/Werkzeugregeln (Systemprompt); die
# globale AGENTS.md von opencode zeigt per Symlink dorthin.
# /opt/captain/defaults: Vorlagen, mit denen compose.deploy.yml ein leeres
# $CAPTAIN_HOME/config befuellt.
COPY config/opencode.jsonc config/AGENTS.md /opt/captain/defaults/
RUN mkdir -p /etc/captain \
 && cp /opt/captain/defaults/* /etc/captain/ \
 && ln -s /etc/captain/AGENTS.md /root/.config/opencode/AGENTS.md

RUN mkdir -p /tmp/captain /root/.local/share/opencode
WORKDIR /tmp/captain

EXPOSE 4096

# Healthcheck ohne curl (Node ist ohnehin da)
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:4096/api/info',{headers:{authorization:'Basic '+Buffer.from('opencode:'+(process.env.OPENCODE_SERVER_PASSWORD||'')).toString('base64')}}).then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"

# Startet immer `opencode serve` (Kommando fest in start.mjs).
ENTRYPOINT ["node", "/opt/captain/start.mjs"]
