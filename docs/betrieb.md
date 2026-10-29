# Captain – Betrieb

Captain auf einem Linux-Rechner mit Docker gegen ein bestehendes Mattermost.
`compose.deploy.yml` (Projekt `captain-prod`) startet `opencode` und den Bot;
gesteuert wird alles über `.env` (Vorlage `.env.example`, dort jede Variable
kommentiert), MCP-Geheimnisse stehen getrennt in `mcp.env`.

## Voraussetzungen

- Docker Engine + Compose-Plugin.
- Der Rechner erreicht Mattermost per HTTPS **und WebSocket**. Hinter nginx:
  `proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";`. Mattermost muss den Rechner nicht
  erreichen.
- Ein OpenAI-kompatibler Endpunkt mit **Tool-Calling**:
  - vLLM: `LLM_BASE_URL=https://vllm…/v1`, Server mit
    `--enable-auto-tool-choice --tool-call-parser <parser>`,
    `LLM_MODEL` = `--served-model-name`, `LLM_CONTEXT` = `--max-model-len`.
  - Open WebUI: `LLM_BASE_URL=https://webui…/api`, Key unter Einstellungen →
    Konto (Admin muss API-Keys erlauben), `LLM_MODEL` = Modell-ID.

## Mattermost einrichten

1. System Console → Integrationen → Bot-Accounts: Erstellung aktivieren.
2. Bot `captain` anlegen, Access Token → `MM_BOT_TOKEN` (Personal Access
   Tokens müssen dafür nicht aktiv sein).
3. Bot ins Team und in die Kanäle holen (`/invite @captain`, private Kanäle
   einzeln). DMs gehen sofort.

## Installieren und starten

```sh
git clone <repo-url> /opt/captain-src && cd /opt/captain-src
cp .env.example .env && chmod 600 .env && $EDITOR .env
docker compose -f compose.deploy.yml up -d --build
docker compose -f compose.deploy.yml ps                # opencode healthy, captain running
docker compose -f compose.deploy.yml logs -f captain
```

Smoke-Test: DM an `@captain`, dann `@captain Hallo` in einem Kanal, dort `!help`.

### Die wichtigsten Variablen

| Variable | Default | Bedeutung |
|---|---|---|
| `CAPTAIN_HOME` | `/opt/captain` | alle Daten (Backup-Ziel) |
| `CAPTAIN_UID`, `CAPTAIN_GID` | `1000` | Nutzer, als der Bot und opencode laufen und dem `CAPTAIN_HOME` gehört (`id -u`, `id -g`) |
| `MM_URL`, `MM_BOT_TOKEN` | – | Mattermost |
| `OPENCODE_SERVER_PASSWORD` | – | Bot ↔ opencode (`openssl rand -hex 24`) |
| `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` | – | Modell-Endpunkt |
| `LLM_CONTEXT`, `LLM_OUTPUT` | `32768`, `4096` | Kontext- und Ausgabegrenze in Tokens (damit opencode rechtzeitig kompaktiert) |
| `OPENCODE_MODEL`, `OPENCODE_VARIANT` | `llm/$LLM_MODEL` | Standardmodell |
| `CAPTAIN_SYSTEM_PROMPT[_FILE]` | neutral | Persona, Datei z. B. `/config/persona.md` |
| `CAPTAIN_API_TOKEN` | leer = aus | REST-Kanal, mind. 24 Zeichen (`openssl rand -hex 32`) |
| `CAPTAIN_API_PUBLISH` | `127.0.0.1:8090` | Host-Adresse der REST-API |
| `CAPTAIN_REST_AGENTS` | leer = keiner | per REST wählbare opencode-Agenten, kommagetrennt (siehe [Agenten](#agenten-im-rest-kanal)) |
| `CAPTAIN_AGENT_TOOLS` | leer | Tools nur für diese Agenten: `reviewer: tool tool; agent2: tool` |
| `CATCHUP_MAX_AGE` | `24h` | Verpasstes höchstens so alt nachholen, `0` = nie |
| `SESSION_MAX_IDLE` | `14d` | Sessions so lange unbenutzt → aufräumen, `0` = nie (siehe [Aufräumen](#aufräumen)) |
| `HISTORY_MAX_POSTS`, `HISTORY_MAX_CHARS` | `50`, `8000` | Vorgeschichte neuer Kanal-Threads |
| `MAX_ATTACHMENT_MB` | `20` | größter Anhang, `0` = keine Grenze |
| `CAPTAIN_WEBFETCH_ALLOW` | leer = aus | URL-Präfixe für `webfetch` |
| `CAPTAIN_SHARED_DIR` | leer = aus | geteiltes Verzeichnis, nur lesen |
| `CAPTAIN_HTTP(S)_PROXY`, `CAPTAIN_NO_PROXY`, `CAPTAIN_CA_FILE` | – | Proxy und eigene CA |

opencode bekommt nur die in `compose.deploy.yml` genannten Variablen plus
`mcp.env` – nie `MM_BOT_TOKEN`. In der Admin-Config lassen sich per
`{env:…}` nur `LLM_*`, `MCP_*` und `OPENCODE_MODEL`/`_VARIANT` einsetzen.

## `CAPTAIN_HOME`

```
$CAPTAIN_HOME/
├─ config/    opencode.jsonc, AGENTS.md, agents/<name>.md, ggf. persona.md, CA   (editierbar)
├─ data/      sessions.json (Session-Store), cursors.json (Nachhol-Cursor)
├─ sessions/  Session-Verzeichnisse (im Container /tmp/captain/<session-id>)
├─ shared/    empfohlener Ort für CAPTAIN_SHARED_DIR
└─ opencode/  opencode.db (Sessions, Nachrichten)
```

Der Service `init` legt das bei jedem Start an, kopiert **fehlende** Vorlagen
nach `config/` (vorhandene Dateien bleiben, neue Vorlagen also selbst aus
`infra/opencode/config/` übernehmen) und gibt alles `CAPTAIN_UID:GID`
(`700`, `CAPTAIN_HOME` und `shared/` `755`). Kein `sudo` mehr nötig. Nur
`init` läuft als root, Bot und opencode nicht.

**Neu aufsetzen ohne Nachholen:** Stack stoppen, `data/cursors.json` löschen
(erster Start = nichts nachholen). `sessions.json` löschen vergisst zusätzlich
alle Unterhaltungen.

## REST-Eingabekanal

Aktiv mit `CAPTAIN_API_TOKEN`; Port 8090, veröffentlicht unter
`CAPTAIN_API_PUBLISH` (für n8n auf einem anderen Rechner z. B. `0.0.0.0:8090`,
dann besser hinter TLS-Proxy).

| Aufruf | Antwort |
|---|---|
| `POST /api/conversations/{id}/messages` `{"text", "sender"?, "callback_url"?, "agent"?}` | `200 {"conversation", "session", "answer"}` |
| … mit `callback_url` | sofort `202`, später `POST` an die URL mit demselben JSON (bei Fehler `"error"`), Header `X-Captain-Conversation`, 3 Wiederholungen |
| `DELETE /api/conversations/{id}` | `200` vergessen (wie `!neu`), `404` unbekannt |
| `GET /api/health` | `200`, ohne Token |

- Header `Authorization: Bearer <CAPTAIN_API_TOKEN>`.
- `id`: `[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}`, frei wählbar
  (`gitlab:42:mr-7`). Gleiche ID = gleiche Session, auch nach Neustart.
- Anfragen derselben ID laufen nacheinander, verschiedene parallel
  (höchstens 64 offen, sonst `503`).
- Fehler: `400` Eingabe oder Agent nicht erlaubt, `401` Token, `409`
  abgebrochen oder anderer Agent, `502` opencode, `413` über 1 MiB.
- Steuerung per Text: Was das Modell tun soll (Review, Format, Sprache),
  steht einfach in `text`.

Beispiel n8n: GitLab-Webhook → HTTP-Request an
`/api/conversations/gitlab:{{project}}:mr-{{iid}}/messages` mit
`callback_url` eines zweiten n8n-Webhooks. Mit dem Agenten `reviewer` postet
die Session selbst in den MR (siehe [Agenten](#agenten-im-rest-kanal)), der
Callback meldet dann nur Erfolg bzw. Fehler. Ohne Agent kann n8n `answer`
selbst als MR-Kommentar posten. Folgekommentare an dieselbe ID landen in
derselben Session.
Beim MR-Close/Merge `DELETE /api/conversations/{id}` – sonst räumt spätestens
der Janitor auf (`SESSION_MAX_IDLE`); ein Kommentar danach startet eine
Session **ohne** Verlauf. Mit dem Umschlag (siehe unten) ist das unkritisch,
der Reviewer holt sich MR und Discussions selbst.

## Agenten im REST-Kanal

`"agent": "reviewer"` lässt die Unterhaltung mit einem eigenen opencode-Agenten
laufen (eigener Systemprompt, eigene Tools). n8n nennt nur den Namen, Captain
entscheidet:

- Erlaubt sind nur Namen aus `CAPTAIN_REST_AGENTS`, sonst `400`. Mattermost
  wählt nie einen Agenten.
- Der Agent wird beim ersten POST am Key gemerkt (`sessions.json`, Feld
  `agent`) und beim Anlegen der opencode-Session gesetzt. Folge-POSTs ohne
  `agent` laufen mit ihm weiter.
- Anderer Agent als bisher, oder `agent` für eine Unterhaltung, die schon
  ohne läuft → `409`. Kein stiller Neustart, weil der den Verlauf verwerfen
  würde. Wechseln: erst `DELETE`, das vergisst auch den Agenten. Der Janitor
  behält ihn.
- Agent später aus `CAPTAIN_REST_AGENTS` entfernt → `400`, bis `DELETE`.

**Definition** in zwei Teilen (Beispiel `reviewer` in der Vorlage):

- `config/opencode.jsonc`, Block `agents`: Name plus `description`,
  `model`, `steps`. Immer `mode: "primary"`, keine eingebauten Namen
  (`build`, `plan`, …), keine `permissions`.
- `config/agents/<name>.md`: der Prompt als normales Markdown. Höchstens
  64 KB, kein Symlink, keine `{env:…}`/`{file:…}` (sonst wird der Agent
  verworfen). Steht zusätzlich `system` in der Config, gewinnt das, und das
  Log warnt.

```jsonc
"agents": {
  "reviewer": { "description": "Statisches Review von GitLab-Merge-Requests", "steps": 60 }
}
```

Änderungen am Prompt wirken nach `docker compose -f compose.deploy.yml restart
opencode` und nur für neue Sessions. `init` kopiert die Vorlage
`agents/reviewer.md`, wenn sie fehlt. Eigene Dateien bleiben unangetastet.

**Rechte:** Wie jede Session nur Dateien im eigenen Verzeichnis, **keine
Shell**. Dazu kommen nur die Tools aus `CAPTAIN_AGENT_TOOLS` für diesen Agenten
(MCP-Tools `<server>_<tool>`, `*` am Ende erlaubt, eingebaute Aktionen wie
`shell` werden beim Start abgelehnt). Alle anderen Sessions, auch Mattermost,
bekommen diese Tools ausdrücklich **nicht**, selbst wenn die Admin-Config sie
global freigibt. Die Regeln stehen beim Anlegen der Session fest. Nach einer
Änderung gelten sie also erst für neue Sessions.

**GitLab-Review** (Beispiel): Ein Remote-MCP-Server mit GitLab-Tools (z. B.
`zereight/gitlab-mcp` per HTTP oder der eigene MCP-Server) läuft mit dem
Bot-Token (Rolle Reporter, Scopes `api` + `read_repository`). Der Token
bleibt im MCP-Server bzw. in `mcp.env` (`{env:MCP_…}` im Header) und
erreicht weder Nachricht noch Session.

```sh
# .env
CAPTAIN_REST_AGENTS=reviewer
CAPTAIN_AGENT_TOOLS=reviewer: gitlab_get_merge_request gitlab_get_merge_request_diffs gitlab_get_file_contents gitlab_create_merge_request_note
```

Die Tool-Namen hängen vom MCP-Server ab (`<server>_<tool>`; Server-Name aus
`mcp.servers`). Der Reviewer liest Diff und Dateien über diese Tools, legt
Notizen im Session-Verzeichnis ab und postet selbst einen Sammelkommentar.

### Umschlag (n8n → Reviewer)

n8n schickt **keinen Diff**, nur einen kleinen Umschlag als JSON im Feld
`text`. Große Diffs würden `MAX_TEXT` sprengen, und die GitLab-API kürzt sie
ohnehin. Den Rest holt der Agent selbst über die GitLab-Tools. Die
Conversation-ID ist fest pro MR: `gitlab:<projekt-id>:mr-<iid>`. Alle Events
eines MR gehen an dieselbe ID, Close/Merge → `DELETE`.

```json
POST /api/conversations/gitlab:42:mr-7/messages
{
  "agent": "reviewer",
  "sender": "GitLab",
  "callback_url": "https://n8n.intern/webhook/captain-review",
  "text": "{\"captain_review\": 1, \"event\": \"open\", \"project\": {\"id\": 42, \"path\": \"team/service\"}, \"mr\": {\"iid\": 7, \"url\": \"https://gitlab.intern/team/service/-/merge_requests/7\", \"title\": \"…\", \"description\": \"…\", \"author\": \"mmuster\", \"source_branch\": \"feature/x\", \"target_branch\": \"main\", \"diff_refs\": {\"base_sha\": \"…\", \"start_sha\": \"…\", \"head_sha\": \"…\"}}}"
}
```

Der Umschlag aufgeklappt:

```json
{
  "captain_review": 1,
  "event": "open",
  "project": { "id": 42, "path": "team/service" },
  "mr": {
    "iid": 7,
    "url": "https://gitlab.intern/team/service/-/merge_requests/7",
    "title": "…", "description": "…", "author": "mmuster",
    "source_branch": "feature/x", "target_branch": "main",
    "diff_refs": { "base_sha": "…", "start_sha": "…", "head_sha": "…" }
  },
  "push": { "oldrev": "…", "newrev": "…" },
  "note": { "id": 1234, "discussion_id": "…", "author": "kkollege", "body": "…" },
  "pipeline": { "id": 5678, "status": "failed" }
}
```

| `event` | Wann | Zusatzfeld | Der Agent … |
|---|---|---|---|
| `open` | MR geöffnet (oder Draft → Ready) | – | prüft den ganzen MR, postet einen Sammelkommentar |
| `update` | neuer Push | `push` | prüft nur `oldrev..newrev`, ergänzt einen Kommentar |
| `note` | Kommentar, der den Bot erwähnt bzw. auf ihn antwortet | `note` | antwortet in derselben Discussion |
| `pipeline` | Pipeline fertig | `pipeline` | wertet Ergebnis und Job-Logs aus, falls relevant |

Felder kommen 1:1 aus dem GitLab-Webhook (`object_attributes`, `project`,
`user`). `captain_review` ist die Formatversion. Selbst holt der Agent: Diff
zwischen den SHAs, Dateien des Source-Branches, Pipeline-Status und
Job-Logs (Ersatz fürs Bauen), bestehende Discussions (nichts doppelt
anmerken), ggf. das verlinkte Ticket und Review-Richtlinien aus der
Wissensbasis. Die passenden Tools müssen in `CAPTAIN_AGENT_TOOLS` stehen.

## Aufräumen

Stündlich löscht ein Janitor jede Session, die länger als `SESSION_MAX_IDLE`
(Default `14d`) keinen Prompt bekam: opencode-Session (`DELETE
/api/session/{id}`), Session-Verzeichnis und Eintrag in `sessions.json`. Ein
per `!modell` gesetztes Modell bleibt. `!neu` und `DELETE
/api/conversations/{id}` löschen die opencode-Session ebenfalls sofort.

- Zählt pro Key `last_used` (jeder Prompt). Einträge von vor dem Update
  gelten ab dem ersten Lauf als genutzt.
- Laufende oder wartende Antworten werden übersprungen (nächster Lauf).
- Ist opencode nicht erreichbar, bleibt alles stehen.
- `cursors.json` bleibt unberührt – Nachholen und „schon beantwortet“
  funktionieren wie vorher.

Neue Nachricht nach dem Aufräumen:

| Kanal | Verhalten |
|---|---|
| Thread (`matter-th:`) | neue Session mit den letzten 20 Thread-Posts als Kontext |
| DM (`matter-dm:`) | neue Session mit den letzten 20 DM-Posts (seit dem letzten `!neu`) als Kontext |
| REST (`rest:`) | neue Session ohne Verlauf, Kontext liefert der Aufrufer |

## opencode-Config

`$CAPTAIN_HOME/config/opencode.jsonc` ist editierbar, danach
`docker compose -f compose.deploy.yml restart opencode`. Das Startskript
(`infra/opencode/start.mjs`) übernimmt nur erlaubte Schlüssel:

- `model`, `providers.*` (Pakete nur `@ai-sdk/openai-compatible`,
  `@opencode/ai/providers/openai-compatible`; `settings`, `headers`,
  `models.*.limit`), weitere Modelle nur für `!modell`-Wechsel
- `mcp.servers.*` nur `type: "remote"`
- `permissions` (`{action, resource, effect}`), `compaction`
- `agents.<eigener Name>` nur `system`, `description`, `model`, `steps`;
  Prompt besser in `agents/<name>.md` (siehe [Agenten](#agenten-im-rest-kanal))

Alles andere (`plugins`, lokale MCP-Server, eingebaute Agenten und
Agent-Rechte, `commands`, `skills`,
`instructions`, `policies`, …) wird mit Warnung verworfen – das würde die
Sicherheit aushebeln. Darüber liegen die Sicherheitsbasis im Image
(`* * deny`, Policies gegen Shell, Websuche, Subagenten, Skills) und die
Session-Regeln des Bots. Die Admin-Config kann darum MCP-Tools freigeben,
Shell & Co. aber nie zurückholen (`ConfigLayersIntegrationTest`).

`config/AGENTS.md` enthält kanalneutrale Stilregeln. Persona
(`captain-persona`), Werkzeuge (`captain-umgebung`) und Formatierung
(`captain-kanal`) setzt der Bot pro Session – Änderungen daran gelten für
**neue** Sessions (`!neu`).

## MCP-Server

Nur Remote-Server (HTTP/Streamable HTTP), jedes Tool einzeln freigeben:

```jsonc
"mcp": { "servers": { "wetter": {
  "type": "remote", "url": "https://mcp.example.org/mcp",
  "headers": { "Authorization": "Bearer {env:MCP_WETTER_TOKEN}" } } } },
"permissions": [ { "action": "wetter_vorhersage", "resource": "*", "effect": "allow" } ]
```

Aktion = `<server>_<tool>`; Server nicht `opencode…` nennen. Geheimnisse nach
`mcp.env` (Name `MCP_…`), dann `up -d`. Probelauf mit `infra/mcp-test/`.

- Interne MCP-Hosts in `CAPTAIN_NO_PROXY`, sonst geht der Aufruf über den
  Proxy (Fehlerbild: HTML-Fehlerseite des Proxys, `HTTP 503`).
- Config-Änderungen wirken erst nach `restart opencode`. Server-Schalter heißt
  `"disabled": true` (`enabled` kennt opencode 2.x nicht).

**Status prüfen** (im Image gibt es kein `curl`, aber `node`):

```sh
docker compose -f compose.deploy.yml exec opencode node -e 'fetch("http://127.0.0.1:4096/api/mcp?location[directory]=/tmp/captain",{headers:{authorization:"Basic "+Buffer.from("opencode:"+process.env.OPENCODE_SERVER_PASSWORD).toString("base64")}}).then(r=>r.text()).then(console.log)'
```

Jeder Server sollte `"status":"connected"` haben; bei `failed` steht der Grund
in `error`. Steht dort noch ein alter Servername, läuft opencode mit der alten
Config.

## webfetch

```sh
CAPTAIN_WEBFETCH_ALLOW=http://text-example.org, https://docs.example.org/handbuch
```

Ein Eintrag erlaubt genau Schema + Host + Port und Pfade darunter
(`/handbuch`, `/handbuch/…`, nicht `/handbuch-alt`); keine Platzhalter,
Userinfo oder `..`. Ungültig → Bot und opencode starten nicht. Drei Sperren:
Session-Regeln, Policies und ein Egress-Filter, der auch Weiterleitungen auf
fremde Hosts abfängt (opencode selbst folgt ihnen ungeprüft,
`WebfetchIntegrationTest`).

**Risiko:** Das Modell kann Inhalte in URL-Parameter erlaubter Hosts schreiben
(auch per Prompt-Injection). Nur Hosts freigeben, denen man vertraut.

## Geteiltes Verzeichnis

```sh
mkdir -p /opt/captain/shared/infos && cp handbuch.md /opt/captain/shared/infos/
echo 'CAPTAIN_SHARED_DIR=/opt/captain/shared/infos' >> .env
docker compose -f compose.deploy.yml up -d
```

- In allen Chats unter `/shared` lesbar (`:ro`), Änderungen sofort sichtbar.
- Text ja, PDF/Office nein (vorher nach `.txt`/`.md` wandeln).
- Unter `CAPTAIN_HOME` nur `shared/…`, sonst absolut und kein
  Systemverzeichnis.
- **Verboten darin:** Symlinks, Mountpoints, harte Links, Geräte,
  Bare-Repos, `.git-credentials`/`.netrc`. Das Startskript prüft alle 10 s
  und stoppt opencode bei einem Fund (Grenzen
  `CAPTAIN_SHARED_MAX_ENTRIES`/`_SECONDS`).
- `.git` wird nie gelesen. Repos als normalen Clone:
  `git clone -c core.symlinks=false …`. `CAPTAIN_SHARED_DIR` selbst darf ein
  Symlink sein (nach Zieländerung `up -d --force-recreate opencode`).
- **Risiko:** Jeder, der Captain schreiben kann, liest alles darin. Keine
  Zugangsdaten ablegen.

## Proxy und eigene CA

- **Build** läuft im Host-Netz; Docker muss den Proxy kennen
  (`~/.docker/config.json` → `proxies`). Gebraucht werden Docker Hub,
  npmjs, Maven Central, Gradle-Plugin-Portal, github.com.
- **Laufzeit** nutzt nur `CAPTAIN_HTTP(S)_PROXY` (Host-Proxy wird bewusst
  ignoriert); interne Ziele in `CAPTAIN_NO_PROXY`. Gilt auch für webfetch und
  Callbacks.
- **Eigene CA** (`UNABLE_TO_VERIFY_LEAF_SIGNATURE` im opencode-Log): PEM nach
  `$CAPTAIN_HOME/config/`, `CAPTAIN_CA_FILE=<datei>`. Am einfachsten das Bündel
  des Hosts (Debian `/etc/ssl/certs/ca-certificates.crt`, SLES
  `/var/lib/ca-certificates/ca-bundle.pem`).
- **DNS im Container kaputt** (`docker run --rm busybox nslookup <host>`):
  in `/etc/docker/daemon.json` `"dns": [...]` setzen, Docker neu starten.

## Update, Backup, Deinstallation

- **Update:** `git pull && docker compose -f compose.deploy.yml up -d --build`.
  Neue Vorlagen in `infra/opencode/config/` mit `config/` abgleichen.
- **Backup:** `$CAPTAIN_HOME`, `.env`, `mcp.env` (am besten bei gestopptem
  Stack).
- **Weg damit:** `docker compose -f compose.deploy.yml down --rmi all`,
  `CAPTAIN_HOME` löschen, Bot-Account deaktivieren.

## Entwicklung und Tests

Java 21 + Gradle-Wrapper, Node ≥ 18 für die Startskript-Tests.

```sh
./gradlew test              # Unit- und Node-Tests
./gradlew integrationTest   # Tag "integration"
./gradlew bootJar           # build/libs/captain.jar
```

| Integrationstest | braucht |
|---|---|
| `ConfigLayersIntegrationTest`, `WebfetchIntegrationTest`, `SharedIntegrationTest` | Docker + Image `captain/opencode:2.2.0` (`docker compose -f compose.deploy.yml build`) |
| `OpencodeIntegrationTest`, `RestrictIntegrationTest` | laufendes Test-opencode mit Modell |
| `MattermostIntegrationTest` | Test-Mattermost (`infra/mattermost/seed.py`) |

Test-Setup: `compose.yml` mit `infra/mattermost` und `infra/opencode`
(Details in deren READMEs); `scripts/e2e.py` testet den laufenden Bot von
außen (`--restart`, `--attachments`).
