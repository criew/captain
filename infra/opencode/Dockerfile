# opencode im Server-Modus (headless) fuer Captain (Test-Setup und Betrieb).
FROM node:22-bookworm-slim

# Version fest pinnen (siehe README.md, Abschnitt "Version").
ARG OPENCODE_VERSION=2.0.20

# git: Snapshots/VCS-Tools, ripgrep: Such-Tools, curl: Healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git ripgrep \
 && rm -rf /var/lib/apt/lists/*

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

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
  CMD curl -fsS -u "opencode:${OPENCODE_SERVER_PASSWORD}" http://127.0.0.1:4096/api/info >/dev/null || exit 1

# Startet immer `opencode serve` (Kommando fest in start.mjs).
ENTRYPOINT ["node", "/opt/captain/start.mjs"]
