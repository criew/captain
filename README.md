# Captain

Mattermost-Bot, der jede Nachricht an einen **opencode**-Server (Server-Modus)
weiterreicht und die Antwort live in *einem* Post zurückschreibt. In
Direktnachrichten antwortet er auf alles, in Kanälen und Gruppen-DMs nur auf
`@captain` – dann im Thread. Pro DM und Thread gibt es eine eigene, dauerhafte
opencode-Session.

## Aufbau

```
compose.deploy.yml        Betrieb: opencode + Bot gegen ein bestehendes Mattermost
.env.example              Vorlage der Betriebs-.env
compose.yml               Test-Setup (bindet infra/*/compose.yml ein) + Bot-Service
Dockerfile                Bot-Image (python:3.12-slim)
infra/mattermost/         Mattermost 9.11.15 + Postgres   (Port 8065)
infra/opencode/           opencode serve (Image, Sicherheitsbasis, Config-Vorlagen)
captain/
├─ config.py              Konfiguration aus Umgebung (+ optional JSON)
├─ sessions.py            Session-Store und Lock pro Session
├─ mattermost.py          Mattermost-Client (REST + WebSocket), PostStreamer
├─ opencode.py            opencode-Client (REST + SSE, API v2)
├─ routing.py             Wann/wo antworten: should_answer, Antwort-Thread, Session-Key, Vorgeschichte
├─ bot.py                 Bot-Logik: Warteschlange pro Session, Befehle, Streaming, Nachholen
├─ catchup.py             Nachholen verpasster Posts: Ablauf, Startpunkt, Auswahl
├─ cursors.py             Cursor pro Kanal, beantwortete Posts (DATA_DIR/cursors.json)
├─ __main__.py            Einstieg: python -m captain
scripts/e2e.py            Ende-zu-Ende-Test gegen das laufende Setup
tests/                    pytest (fake_llm.py: Fake-LLM, mcp_testserver/: Test-MCP-Server)
```

## Betrieb mit bestehendem Mattermost

Captain auf einem separaten Linux-Rechner mit Docker gegen ein vorhandenes
Mattermost betreiben (kein Test-Mattermost, kein Seed). `compose.deploy.yml`
startet nur `opencode` und den Bot; gesteuert wird alles über die `.env`
daneben, Secrets für MCP-Server stehen getrennt in `mcp.env` (bekommt nur
opencode).

### 1. Voraussetzungen

- Linux mit Docker Engine und Compose-Plugin (`docker compose version`).
- Der Rechner erreicht Mattermost per HTTPS **und WebSocket**
  (`wss://chat.example.org/api/v4/websocket`). Hinter einem Reverse-Proxy
  müssen dafür die `Upgrade`/`Connection`-Header durchgereicht werden (nginx:
  `proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";`), sonst meldet der Bot
  WebSocket-Fehler und verbindet sich endlos neu.
- Mattermost muss den Bot-Rechner **nicht** erreichen (keine Webhooks, keine
  Slash-Commands); ausgehende Verbindungen genügen.
- Ein Modell mit Tool-Calling (siehe 3.).

### 2. Mattermost (als Admin)

1. Bot-Accounts erlauben: System Console → Integrationen → Bot-Accounts →
   „Bot-Account-Erstellung aktivieren“ (`ServiceSettings.EnableBotAccountCreation`).
2. Integrationen → Bot-Accounts → „Bot-Account hinzufügen“: Benutzername
   `captain`, Anzeigename „Captain“. Das angezeigte **Access Token** sichern
   (wird nur einmal gezeigt) → `MM_BOT_TOKEN`.
3. Bot ins Team aufnehmen (Team-Menü → Mitglieder hinzufügen) und in die
   gewünschten Kanäle (`/invite @captain` im Kanal); **private Kanäle**
   jeweils ausdrücklich. DMs an `@captain` funktionieren ohne Weiteres.

„Personal Access Tokens“ (`ServiceSettings.EnableUserAccessTokens`) müssen
**nicht** aktiviert sein: Bot-Tokens sind davon ausgenommen (geprüft mit
9.11.15: bei ausgeschalteter Einstellung lässt sich ein Bot-Token anlegen und
nutzen, ein Nutzer-Token wird mit 401 abgelehnt).

### 3. Modell

Standard ist ein **OpenAI-kompatibler Chat-Endpunkt mit Bearer-Token**
(Provider `llm` in `$CAPTAIN_HOME/config/opencode.jsonc`):

| Server | `LLM_BASE_URL` | `LLM_API_KEY` | Hinweise |
|---|---|---|---|
| vLLM | `https://vllm.example.org/v1` | `--api-key` des Servers | Tool-Calling muss serverseitig an sein: `--enable-auto-tool-choice --tool-call-parser <parser>` (z. B. `hermes`, `llama3_json`, `mistral`), sonst funktionieren Datei- und MCP-Tools nicht. `LLM_MODEL` = `--served-model-name` |
| Open WebUI | `https://webui.example.org/api` (Endpunkt `/api/chat/completions`) | Einstellungen → Konto → API-Schlüssel (der Admin muss API-Schlüssel vorher erlauben: Admin-Bereich → Einstellungen → Allgemein) | `LLM_MODEL` = Modell-ID in Open WebUI; das Modell dahinter braucht Tool-Support |
| Ollama | `http://host.docker.internal:11434/v1` | beliebig | Kontextlänge (`OLLAMA_CONTEXT_LENGTH`) beachten |

`limit.context` in `opencode.jsonc` an die echte Kontextlänge des Servers
anpassen (vLLM: `--max-model-len`), damit opencode rechtzeitig kompaktiert.
Alternativ den API-Key eines Cloud-Providers in die `.env` eintragen
(`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OPENROUTER_API_KEY`) und
`OPENCODE_MODEL` setzen.

### 4. Installieren

```sh
git clone <repo-url> /opt/captain-src && cd /opt/captain-src
cp .env.example .env && chmod 600 .env
$EDITOR .env      # MM_URL, MM_BOT_TOKEN, OPENCODE_SERVER_PASSWORD, LLM_*, Persona …
```

Variablen der `.env` (Details: `.env.example` und „Konfiguration“):

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `CAPTAIN_HOME` | nein (`/opt/captain`) | Host-Verzeichnis für alle Daten |
| `MM_URL`, `MM_BOT_TOKEN` | ja | Mattermost-URL und Bot-Token |
| `OPENCODE_SERVER_PASSWORD` | ja | Passwort zwischen Bot und opencode (z. B. `openssl rand -hex 24`) |
| `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` | ja* | Modell-Endpunkt (*oder Cloud-API-Key + `OPENCODE_MODEL`) |
| `OPENCODE_MODEL`, `OPENCODE_VARIANT` | nein | Standardmodell, Default `llm/$LLM_MODEL` |
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OPENROUTER_API_KEY` | nein | Cloud-Provider |
| `CAPTAIN_SYSTEM_PROMPT` / `CAPTAIN_SYSTEM_PROMPT_FILE` | nein | Persona (Datei z. B. `/config/persona.md` = `$CAPTAIN_HOME/config/persona.md`) |
| `ALLOWED_USERS` | nein | nur diese Nutzer (kommagetrennt) |
| `CATCHUP_MAX_AGE` | nein (`24h`) | verpasste Nachrichten höchstens so alt nachholen |
| `HISTORY_MAX_POSTS`, `HISTORY_MAX_CHARS` | nein (50 / 8000) | Vorgeschichte neuer Kanal-Unterhaltungen |
| `MAX_ATTACHMENT_MB` | nein (20) | größter Anhang in MB, der geladen und an opencode gegeben wird (`0` = keine Grenze) |
| `LOG_LEVEL` | nein (`INFO`) | Log-Level des Bots |
| `CAPTAIN_HTTP_PROXY`, `CAPTAIN_HTTPS_PROXY`, `CAPTAIN_NO_PROXY` | nein | Proxy für ausgehende Verbindungen (siehe „Hinter einem Proxy“) |
| `MCP_…` in **`mcp.env`** | nein | Geheimnisse für MCP-Server (Vorlage `mcp.env.example`; `{env:MCP_…}` in der Config) |

Der Bot bekommt die ganze `.env`. Der opencode-Container bekommt davon nur
die in `compose.deploy.yml` genannten Variablen (`OPENCODE_SERVER_PASSWORD`,
`OPENCODE_MODEL`/`_VARIANT`, `LLM_*`, `OLLAMA_BASE_URL`, Cloud-API-Keys) plus
`mcp.env` – also nie `MM_BOT_TOKEN`. In der Admin-Config lassen sich per
`{env:NAME}` nur `LLM_*`, `OLLAMA_*`, `MCP_*`, `*_API_KEY` und
`OPENCODE_MODEL`/`OPENCODE_VARIANT` einsetzen, nie `MM_*`, `CAPTAIN_*` oder
`OPENCODE_SERVER_PASSWORD`. Die Sicherheitsschalter setzt das Startskript
fest.
Gleichnamige Variablen aus der Shell haben bei der Auswertung von
`compose.deploy.yml` Vorrang vor der `.env`. Das Compose-Projekt heißt
`captain-prod` (kollidiert nicht mit dem Test-Setup `captain`).

### 5. Starten und prüfen

```sh
docker compose -f compose.deploy.yml up -d --build
docker compose -f compose.deploy.yml ps                 # opencode healthy, captain running
docker compose -f compose.deploy.yml logs -f captain    # „Captain läuft als @captain (…)“
```

Smoke-Test: `@captain` eine DM schreiben (Antwort direkt im Chat), dann in
einem Kanal `@captain Hallo` (Antwort als Thread unter dem Post), dort
`!help`.

#### Hinter einem Proxy

- **Build:** Die Images werden im Host-Netz gebaut (`build.network: host`),
  `apt-get`/`npm`/`pip` sehen also DNS, `/etc/hosts` und Proxy-Einstellungen
  des Hosts. Braucht der Host für Internetzugriff einen Proxy, muss Docker ihn
  kennen – `~/.docker/config.json` (`"proxies": {"default": {"httpProxy": …,
  "httpsProxy": …, "noProxy": …}}`) oder die Systemd-Konfiguration des
  Docker-Daemons.
  Fehlerbild ohne das: `ProxyError('Cannot connect to proxy.' … Temporary
  failure in name resolution)` bei `pip install` bzw. hängendes `apt-get`.
- **Laufzeit:** Bot und opencode nutzen **nur** `CAPTAIN_HTTP_PROXY` /
  `CAPTAIN_HTTPS_PROXY` aus der `.env` – ein Proxy aus der Host-Shell oder
  `~/.docker/config.json` wird bewusst überschrieben, weil sein Name im
  Container oft nicht auflösbar ist. Den Proxy daher als IP oder per DNS
  auflösbaren Namen angeben. Ziele, die direkt erreichbar sind (internes
  Mattermost, vLLM/Open WebUI), in `CAPTAIN_NO_PROXY` eintragen; die interne
  Verbindung Bot → opencode läuft immer direkt.
- **DNS in Containern prüfen:** `docker run --rm busybox nslookup <mattermost-host>`.
  Meldet das `no servers could be reached`, erreichen Container keinen
  DNS-Server (typisch bei `systemd-resolved` auf dem Host + gesperrtem
  `8.8.8.8`). Dann dem Docker-Daemon die internen DNS-Server mitgeben –
  Adressen aus `resolvectl status` bzw. `/run/systemd/resolve/resolv.conf`:
  ```sh
  # /etc/docker/daemon.json
  { "dns": ["10.1.2.3", "10.1.2.4"], "dns-search": ["firma.intern"] }
  sudo systemctl restart docker
  ```

### 6. `CAPTAIN_HOME`

Beim Start legt der kurzlebige Service `init` die Struktur an (Container laufen
als root, die Verzeichnisse gehören also root) und kopiert **fehlende**
Vorlagen nach `config/` – vorhandene Dateien werden nie überschrieben:

```
$CAPTAIN_HOME/          (Default /opt/captain)
├─ config/     opencode.jsonc, AGENTS.md, ggf. persona.md
│              opencode: /etc/captain (nur lesbar), Bot: /config (nur lesbar)
├─ data/       Bot: sessions.json (Session-Store), cursors.json (Nachhol-Cursor) → /data
├─ sessions/   Session-Verzeichnisse <session-id>/ → beide: /tmp/captain
└─ opencode/   opencode.db (Sessions, Nachrichten) → /root/.local/share/opencode
```

Alle Ordner sind `700` und gehören root; bearbeiten mit `sudo`.

**Container als root:** Bot und opencode laufen als root, weil sie sich das
Session-Verzeichnis teilen. Das Modell kommt an keine Shell und nur an Dateien
im eigenen Session-Verzeichnis (Session-Regeln, Policies, keine
Projekt-Config, bereinigte Admin-Config); ein Ausbruch bräuchte eine Lücke in
opencode selbst. Für diesen Fall begrenzt Docker den Schaden (kein
privilegierter Container, keine Host-Mounts außer `CAPTAIN_HOME`), root im
Container ist aber nicht root-los – den Rechner deshalb nicht für andere
sensible Dienste mitnutzen und Docker aktuell halten.

### 7. opencode-Config anpassen (ohne Rebuild)

opencode 2.0.20 liest mehrere Config-Quellen in fester Reihenfolge (aus dem
Binary ermittelt, per Test belegt):

1. **Sicherheitsbasis im Image** (`infra/opencode/config/base.jsonc` →
   `/root/.config/opencode/opencode.jsonc`): globales `* * deny` und
   `experimental.policies` gegen Shell, Web, Subagenten, Skills, Rückfragen,
   fremde Verzeichnisse und MCP-Ressourcen; Projekt-Config aus.
2. **Admin-Config** `$CAPTAIN_HOME/config/opencode.jsonc` – **editierbar**,
   aber nur über das Startskript `infra/opencode/start.mjs`. Es liest die
   Datei beim Containerstart, setzt `{env:…}` selbst ein (JSON-sicher auf
   Werte-Ebene, nur erlaubte Namen, siehe 4.; opencode würde sonst textuell
   und ohne Escaping ersetzen), verwirft danach übrige `{env:…}`/`{file:…}`
   und übernimmt je Block nur erlaubte Schlüssel:
   - `model` (`provider/modell`)
   - `providers.*`: `name`, `package` (nur in opencode eingebaute Pakete:
     `@ai-sdk/openai-compatible`, `@opencode/ai/providers/<name>` – sonst
     würde opencode Code per `file://` oder npm-Installation laden),
     `settings` (`baseURL`, `apiKey`, `timeout`, `chunkTimeout`,
     `includeUsage`), `headers`, `models.*` (`modelID`, `name`, `disabled`,
     `limit.context/input/output`)
   - `mcp.servers.*` nur `type: "remote"` mit `url`, `headers`, `disabled`,
     `timeout` (`codemode`/`oauth` immer `false`), `mcp.timeout`
   - `permissions` (nur `{action, resource, effect: allow|deny}`)
   - `compaction` (`auto`, `buffer`, `keep.tokens`)

   opencode bekommt eine platzhalterfreie Kopie (`/run/captain/opencode.json`).
   Alles andere verwirft das Skript mit einer WARNUNG im Log von `opencode`;
   ungültiges JSONC oder eine nicht schreibbare Kopie → opencode startet nicht.
3. Feste Schalter (`OPENCODE_CONFIG_CONTENT`, vom Startskript gesetzt: kein
   Teilen, keine Updates, keine Websuche/LSP/Formatter/Snapshots).
4. Pro Session die Regeln des Bots (nur Dateien im eigenen Verzeichnis, zum
   Schluss Verbot von Shell, Web, Subagenten, Skills, Rückfragen, Code Mode,
   MCP-Ressourcen und fremden Verzeichnissen).

Regeln aller Quellen werden aneinandergehängt (die letzte passende gewinnt),
Policies der Sicherheitsbasis gewinnen immer gegen Policies späterer Quellen,
Einzelwerte kommen aus der letzten Quelle. Die Admin-Config kann darum
**freigeben** (z. B. MCP-Tools), Shell & Co. aber nicht zurückholen: Selbst
eine Admin-Config mit `* * allow` und eigenen `allow`-Policies lässt die Shell
unsichtbar, und ohne die Session-Regeln scheitern die Aufrufe an der Policy
(„Blocked by configuration policy“) – belegt durch
`tests/test_config_layers_integration.py`.

Warum die Allowlist: opencode 2.0.20 führt `plugins` (JavaScript im
Server), lokale MCP-Server (`type: "local"` = beliebiger Prozess als root),
`agents` (Rechte/Systemprompt der Agenten), `commands`, `skills`,
`instructions`, `references`, `shell`, `enterprise`, `default_agent` und
`experimental` aus **jeder** Config-Quelle zusammen; eine feste Basis kann
sie nicht überschreiben. Diese Schlüssel **nicht verwenden – sie hebeln die
Sicherheit aus**; das Startskript verwirft sie. Belegt durch
`tests/test_config_layers_integration.py`: Ohne Startskript führt opencode
ein Plugin bzw. einen lokalen MCP-Server aus der Admin-Config aus, mit
Startskript nicht; `{env:…}`-Tricks (`"type": "{env:X}"`, `.env`-Werte mit
`"`, die neue Schlüssel erzeugen würden), `{file:…}` und Verweise auf
`MM_BOT_TOKEN`/`OPENCODE_SERVER_PASSWORD` greifen nicht (dazu Node-Unit-Tests
`infra/opencode/start.test.mjs`, ausgeführt über `tests/test_start_script.py`).

Code Mode (`execute`) prüft opencode nicht über Policies (getestet); gesperrt
ist es durch das globale `* * deny` und die Session-Regeln des Bots.

`$CAPTAIN_HOME/config/AGENTS.md` (Stil- und Werkzeugregeln im Systemprompt)
ist ebenfalls editierbar. Nach Änderungen:

```sh
docker compose -f compose.deploy.yml restart opencode
```

Neue Vorlagen aus einem Update landen nicht automatisch in `config/`
(vorhandene Dateien bleiben); zum Vergleich liegen sie in
`infra/opencode/config/`.

### 8. MCP-Server hinzufügen

Unterstützt sind nur **Remote-MCP-Server (HTTP bzw. Streamable HTTP)**; lokale
stdio-Server nicht (das Image enthält dafür bewusst keine Laufzeiten).
Definiert werden sie nur vom Admin in `$CAPTAIN_HOME/config/opencode.jsonc`
und gelten für alle Chats; das Modell kann keine eigenen hinzufügen.
Freigegeben wird **jedes Tool einzeln** – alles andere bleibt durch das
globale `* * deny` gesperrt und wird dem Modell gar nicht angeboten.

```jsonc
"mcp": {
  "servers": {
    "wetter": {
      "type": "remote",
      "url": "https://mcp.example.org/mcp",
      "headers": { "Authorization": "Bearer {env:MCP_WETTER_TOKEN}" },
      "oauth": false,
      "codemode": false
    }
  }
},
"permissions": [
  { "action": "wetter_vorhersage", "resource": "*", "effect": "allow" }
]
```

- Aktionsname eines Tools: `<server>_<tool>` (andere Zeichen als
  `A-Za-z0-9_-` werden zu `_`), Ressource `*`. Den Server nicht `opencode…`
  nennen (Policy `opencode_*`).
- `"codemode": false` setzt das Startskript immer (sonst böte opencode die
  Tools nur über das gesperrte Skript-Tool `execute` an, Code Mode mit
  eigenem Netzzugriff); `oauth` ist immer `false` (headless).
- Geheimnisse nur in `mcp.env` (`cp mcp.env.example mcp.env`,
  `MCP_WETTER_TOKEN=…`, Name muss mit `MCP_` beginnen), in der Config per
  `{env:MCP_WETTER_TOKEN}`. `mcp.env` bekommt nur der opencode-Container.
- Danach `docker compose -f compose.deploy.yml up -d` (neue `.env`-Werte)
  bzw. `restart opencode` (nur Config). opencode verbindet MCP-Server pro
  Session-Verzeichnis beim ersten Prompt; der Bot wartet darauf kurz
  (`OpencodeClient.ensure_mcp`) und merkt einen Neustart von opencode am
  Wiederverbinden des Event-Streams – ein Neustart des Bots ist nicht nötig.
  Verbindungsfehler stehen im Log von `opencode` bzw. als Warnung im Bot-Log.

Nachweis mit dem Test-Server `tests/mcp_testserver/` (`wuerfeln`
freigegeben, `geheim` nicht): `wuerfeln` funktioniert im Chat, `geheim` und
die Shell werden dem Modell nicht angeboten (Aufrufversuch: „No tool named …“).
Einbinden für einen Probelauf: `compose.deploy.yml` plus
`tests/mcp_testserver/compose.yml` (siehe dort).

### 9. Erster Start, Updates, Backup, Deinstallation

- **Erster Start:** Captain beantwortet nur, was ab dann kommt; ältere
  Nachrichten bleiben unbeantwortet. Nach späteren Pausen (Neustart, Update)
  holt er Verpasstes bis `CATCHUP_MAX_AGE` nach.
- **Update:** `git pull && docker compose -f compose.deploy.yml up -d --build`.
  Sessions und Config bleiben erhalten.
- **Backup:** das ganze `$CAPTAIN_HOME` sichern (am besten bei gestopptem
  Stack: `docker compose -f compose.deploy.yml stop`), dazu `.env` und
  `mcp.env`.
- **Deinstallation:** `docker compose -f compose.deploy.yml down --rmi all`,
  danach das Datenverzeichnis löschen – `sudo rm -rf /opt/captain` bzw. euren
  Pfad aus `CAPTAIN_HOME` – und das Repo-Verzeichnis; in Mattermost den
  Bot-Account deaktivieren.

## Schnellstart (Test-Setup)

Lokale Entwicklungsumgebung mit eigenem Test-Mattermost – getrennt vom Betrieb
oben.

```sh
docker compose up -d mattermost opencode      # 1. Mattermost + opencode (infra/opencode/.env vorher anlegen)
python infra/mattermost/seed.py               # 2. Nutzer, Kanäle, Bot → infra/mattermost/generated.env
docker compose up -d --build captain          # 3. Bot starten
python scripts/e2e.py                         #    optional: Ende-zu-Ende-Test
python scripts/e2e.py --restart               #    optional: Nachholen nach Neustart (stoppt/startet den Bot)
```

Danach im Browser <http://localhost:8065> als `alice` / `Alice-Test-1234`
anmelden und dem Bot `captain` schreiben. Logs: `docker compose logs -f captain`.

## Verhalten

- **Wann er antwortet** (`captain/routing.py`, `should_answer`):

  | Ort | Reaktion | Antwort erscheint |
  |---|---|---|
  | DM mit Captain | jede Nachricht | direkt im Chat |
  | Kanal/Gruppen-DM, Top-Level | nur bei `@captain` (Groß-/Kleinschreibung egal) | als Thread unter dem Post |
  | Thread, in dem Captain geschrieben hat | jede Antwort, ohne Erwähnung | im Thread |
  | Thread ohne Captain | nur bei `@captain` | im Thread |

  Befehle gelten genauso: in Kanälen oben nur als `@captain !help`, in DMs und
  Threads mit Captain auch ohne. Nicht adressierte Posts landen nicht in der
  Warteschlange. Posts anderer Bots und Webhooks (`props.from_bot`/
  `from_webhook`, Bot-Accounts) werden ignoriert, ebenso Nutzer außerhalb von
  `ALLOWED_USERS`.
- **Session-Keys** (eine opencode-Session je Key, in `DATA_DIR/sessions.json`):
  DM → `dm:<channel_id>`, Kanäle/Gruppen: jede Unterhaltung ist ein Thread →
  `th:<root_id>` (eine Top-Level-Erwähnung öffnet einen neuen Thread und damit
  eine neue Session). `ch:<channel_id>` hält nur noch das Kanal-Modell.
- **Session-Verzeichnis** `/tmp/captain/<session-id>` (`SESSIONS_DIR`): eine
  opencode-Session = ein Verzeichnis. Der Bot vergibt die Session-ID selbst
  (`ses_<32 hex>`, opencode v2 akzeptiert eigene IDs), legt das Verzeichnis
  **vor** der Session an und speichert beides im Store. Bei `!neu` und jeder
  neuen Session (auch Selbstheilung nach verschwundener Session) wird das alte
  Verzeichnis gelöscht. Gelöscht wird nur direkt unterhalb von `SESSIONS_DIR`.
- **Restriktives opencode** (Details: `infra/opencode/README.md`): global ist
  alles verboten; pro Session gibt der Bot beim Anlegen nur `read`, `edit`
  (edit/write/apply_patch), `glob`, `grep` für das eigene Verzeichnis frei und
  verbietet zum Schluss ausdrücklich Shell, Web, Subagenten, Skills,
  Rückfragen, Code Mode, MCP-Ressourcen und fremde Verzeichnisse (das
  überstimmt auch Freigaben aus der editierbaren Admin-Config). opencode
  bietet dem Modell dadurch nur `read`, `write`, `edit`, `glob`, `grep` an –
  plus vom Admin freigegebene MCP-Tools.
- **Systemanweisungen pro Session** (dauerhaft, überleben Kompaktierung): der
  Bot setzt nach dem Anlegen über
  `PUT /api/experimental/session/{id}/instructions/entries/{key}` zwei
  Einträge – `captain-persona` (`CAPTAIN_SYSTEM_PROMPT[_FILE]`) und
  `captain-umgebung` (konkretes Verzeichnis, keine Shell/Web). opencode hängt
  sie als `<context key=…>` an den Systemprompt, **nach** der globalen
  `AGENTS.md` (die deshalb keine Persona mehr enthält, nur Stil- und
  Werkzeugregeln). Schlägt das Setzen (auch teilweise) fehl, bekommt der
  **erste Prompt** der Session beides als markierten Vorspann
  `[Systemhinweise für diese Unterhaltung] … [Ende der Systemhinweise]`; beim
  nächsten Prompt versucht der Bot die API einmal erneut (klappt es nicht,
  kommt der Vorspann noch einmal, danach nicht mehr). Ein Vorspann kann durch
  Kompaktierung verloren gehen; die Permissions greifen unabhängig davon.
- **Startkontext einer neuen Thread-Session:** aus einer Top-Level-Erwähnung
  die Kanal-Posts seit Captains letzter Beteiligung im Kanal (jüngster Thread,
  in dem er geantwortet hat; nur Top-Level, ohne Bots; höchstens
  `HISTORY_MAX_POSTS` Posts / `HISTORY_MAX_CHARS` Zeichen, Default 50 / 8000,
  neueste gewinnen), sonst – Erwähnung in einem bestehenden
  Thread – der bisherige Thread (bis 20 Posts). Beides ist im Prompt als
  Vorgeschichte markiert.
- **Gruppen:** an opencode geht `Name: Text` (Anzeigename aus Mattermost),
  eine Anrede `@captain` am Anfang entfällt, weitere Erwähnungen werden zu
  „Captain“ (sonst bleibt der Text unverändert; `@captain` in Code zählt
  nicht). In DMs geht der Text unverändert.
- **Antwortort:** siehe Tabelle. Die Antwort
  wächst live in *einem* Post (gedrosselt); bis zum ersten Text läuft der
  Typing-Indicator, Tool-Aufrufe erscheinen als kursive Statuszeile.
  Zwischenstände enden mit `▌`, der Endstand nicht.
- **Warteschlange:** pro Session läuft immer nur eine Ausführung (opencode
  würde einen zweiten Prompt per `steer` in die laufende Antwort lenken).
  Nachrichten, die währenddessen kommen, bekommen ⏳ und werden danach
  **gebündelt** in einem Prompt beantwortet – bei einem langsamen Modell
  liefert das eine Antwort auf den aktuellen Stand statt mehrerer
  veralteter. Verschiedene Sessions laufen parallel.
- **Fehler:** verschwundene Session → neue Session, ein Wiederholungsversuch.
  Zeitüberschreitung (600 s ohne Event) und andere opencode-Fehler werden mit
  dem bisherigen Text als ⚠️ gemeldet, nicht wiederholt; ein Abbruch erscheint
  als „_(abgebrochen)_“.
- **Anhänge** landen im Arbeitsverzeichnis der Session und gehen als `files`
  an opencode.
- **Nachholen nach Neustart/Verbindungslücke** (`captain/catchup.py`,
  `captain/cursors.py`): Keine an Captain gerichtete Nachricht soll verloren
  gehen, keine doppelt beantwortet werden.
  - *Cursor pro Kanal* in `DATA_DIR/cursors.json` (atomar, gedrosselt): der
    neueste gesehene Post, aber nie über einen angenommenen, noch nicht fertig
    beantworteten Post hinweg (Low-Watermark) – wer beim Beenden in
    Warteschlange, Nachschlagen oder Streaming steckt, wird nach dem Start
    nachgeholt. Beantwortete Post-IDs werden `CATCHUP_MAX_AGE` lang
    gespeichert und übersprungen. Zusätzlich trägt jede fertige Antwort die
    beantworteten Post-IDs in `props.captain_answers` (bei mehrteiligen
    Antworten der letzte Teil, erst nach Abschluss; Zwischenstände und
    abgebrochene Antworten nie) – das deckt einen Absturz ab, bevor der
    Zustand gespeichert war.
  - *Ablauf:* Beim Start und nach jedem WebSocket-Reconnect ohne Replay (neue
    `connection_id` im `hello`) werden Live-Posts gepuffert, die Posts seit
    dem Cursor aller Kanäle (Team-Kanäle, DMs, Gruppen-DMs, inkl.
    Thread-Antworten; Kanäle mit `last_post_at` ≤ Cursor übersprungen) per
    REST geholt, chronologisch durch dieselbe Entscheidung wie live geschickt
    und danach der Puffer abgearbeitet; Doppelte fallen über die Post-ID
    heraus. Antworten darauf beginnen mit „_(verspätet – ich war kurz
    offline)_“ (nicht vor reinen Fehlernotizen).
  - *Fehler:* Scheitert das Nachholen für einen Kanal (oder die Kanalliste),
    bleibt dessen Cursor eingefroren, live geht es weiter, und das Nachholen
    wird mit Backoff (1 s … 60 s) wiederholt.
  - *Beenden* (SIGTERM): Cursor sichern, laufende Antworten abbrechen; ihr
    Zwischenstand endet mit „_(unterbrochen – …)_“ und wird nach dem Start
    neu beantwortet.
  - Höchstens `CATCHUP_MAX_AGE` (24 h) zurück, ältere Posts werden
    übersprungen (Log); `0` = nichts nachholen. Erster Start ohne
    Cursor-Datei: alle Kanäle auf „jetzt“, nichts nachholen (die Datei
    entsteht erst danach). Kanal ohne Cursor: ab Beitritt (DM/Gruppe: ab
    Erstellung; Beitritt länger her: ab Höchstalter), nur an Captain
    gerichtete Posts.

| Befehl | Wirkung |
|---|---|
| `!neu` | neue Session (Verlauf vergessen, Modell bleibt), im Thread für den Thread; reiht sich in die Warteschlange ein. In Kanälen oben nur ein Hinweis – jede Erwähnung beginnt dort ohnehin neu |
| `!modell` | Modelle auflisten; `!modell <provider/modell> [variante]` setzt es für die DM bzw. den Thread – in Kanälen oben (`@captain !modell …`) für alle neuen Threads des Kanals; `!modell standard` setzt zurück |
| `!stopp` | laufende Antwort abbrechen, Wartende verwerfen (sofort); in Kanälen oben (`@captain !stopp`) alle Unterhaltungen des Kanals |
| `!help` / `!hilfe` | Befehle und wann Captain reagiert (sofort) |

## Konfiguration

Werte kommen aus Umgebungsvariablen; optional zusätzlich aus einer JSON-Datei,
deren Pfad in `CAPTAIN_CONFIG` steht (gleiche Schlüssel, Umgebung gewinnt).

| Variable            | Pflicht | Default       | Bedeutung                               |
|---------------------|---------|---------------|-----------------------------------------|
| `MM_URL`            | ja      |               | Basis-URL des Mattermost-Servers         |
| `MM_BOT_TOKEN`      | ja      |               | Token des Bot-Accounts                   |
| `OPENCODE_URL`      | ja      |               | Basis-URL von `opencode serve`           |
| `OPENCODE_PASSWORD` | nein    |               | Passwort des opencode-Servers (Fallback: `OPENCODE_SERVER_PASSWORD`) |
| `OPENCODE_MODEL`    | nein    |               | Standardmodell (`provider/model`)        |
| `OPENCODE_VARIANT`  | nein    |               | Standardvariante des Modells             |
| `SESSIONS_DIR`      | nein    | `/tmp/captain` | Session-Verzeichnisse `<SESSIONS_DIR>/<session-id>` (Sicht von opencode, geteiltes Volume); muss absolut sein und darf nicht `/` sein |
| `CAPTAIN_SYSTEM_PROMPT` | nein | neutrale Captain-Beschreibung | Persona/Systemnachricht jeder Session |
| `CAPTAIN_SYSTEM_PROMPT_FILE` | nein |          | Pfad zu einer Textdatei mit der Persona; Vorrang vor `CAPTAIN_SYSTEM_PROMPT` |
| `DATA_DIR`          | nein    | `data`        | Ablage für Session-Store und Cursor (Container: `/data`) |
| `ALLOWED_USERS`     | nein    | leer = alle   | Kommagetrennte Mattermost-Usernamen      |
| `CATCHUP_MAX_AGE`   | nein    | `24h`         | Verpasste Posts höchstens so alt nachholen (Sekunden oder `90m`, `24h`, `2d`; `0` = nichts nachholen) |
| `MAX_ATTACHMENT_MB` | nein    | `20`          | Größter Anhang in MB, der geladen und an opencode gegeben wird (Dezimalzahl erlaubt, `0` = keine Grenze) |
| `HISTORY_MAX_POSTS` | nein    | `50`          | Vorgeschichte einer neuen Kanal-Unterhaltung: höchstens so viele Posts (`0` = keine) |
| `HISTORY_MAX_CHARS` | nein    | `8000`        | … und so viele Zeichen insgesamt (neueste gewinnen) |
| `LOG_LEVEL`         | nein    | `INFO`        | Log-Level (nur Umgebung)                 |

Im Container setzt `compose.yml` `MM_URL`/`OPENCODE_URL` auf die
Service-Namen (Vorrang vor den `env_file`s, die `localhost` enthalten) und
teilt das Volume `captain-tmp` unter `/tmp/captain` mit opencode (übersteht
Neustarts). Der Bot läuft als root wie opencode, damit er die Verzeichnisse
anlegen und löschen darf.

Persona-Beispiel (in `infra/opencode/.env`, das der Bot-Container ebenfalls
liest, oder per `environment:`):

```sh
CAPTAIN_SYSTEM_PROMPT="Du bist CaptainSuperman, der helfende Bot, und hast eine lustige Persönlichkeit."
```

Für längere Texte eine Datei ins Bot-Image/-Volume legen und
`CAPTAIN_SYSTEM_PROMPT_FILE=/data/persona.txt` setzen. Die Persona gilt für
**neue** Sessions (bestehende behalten ihre; `!neu` übernimmt die neue).

## Anhänge

Dateien an einem Post lädt Captain ins Verzeichnis der Session
(`/tmp/captain/<session-id>/`) und reicht sie als Datei-Anhang an opencode
weiter; der Prompt nennt zusätzlich die Dateinamen. opencode liest die Datei,
bestimmt den MIME-Typ und gibt sie je nach Modell-Fähigkeit (`capabilities.input`
laut `GET /api/model`) an das Modell. Später kann das Modell die Datei mit
`read` erneut aus dem Session-Verzeichnis lesen.

**Größengrenze:** `MAX_ATTACHMENT_MB` (Default `20`, Dezimalzahl erlaubt,
`0` = keine Grenze). Größere Anhänge werden nicht geladen (Größe laut
Mattermost-Metadaten, zur Sicherheit auch beim Laden geprüft); das Modell
erfährt es im Prompt, und unter der Antwort steht immer
`_(Anhang „name“ nicht geladen: größer als 20 MB)_`.

Befund (`python scripts/e2e.py --attachments`, Ollama 0.34.4, DM und
`@captain` im Kanal):

| Typ | Ergebnis | Warum |
|---|---|---|
| Text (`.txt`) | funktioniert | opencode gibt den Inhalt als Text mit; Folgefrage ohne Anhang beantwortet das Modell per `read` aus dem Session-Verzeichnis |
| Bild (`.png`) | Kette funktioniert, `gemma4:e4b` versteht es nicht | opencode schickt das Bild (`image/png`) ans Modell (Ollama-Log: „image decoded“, 81 Bild-Tokens), `gemma4:e4b` antwortet aber auch direkt über Ollama nur mit erfundenen Beschreibungen; `gemma3:4b` erkennt dasselbe Bild direkt über Ollama, lässt sich über opencode aber nicht nutzen (Ollama: Modell unterstützt keine Tools, opencode schickt immer Tools mit) |
| PDF | funktioniert nicht | opencode nimmt `application/pdf` an, Ollama-Modelle haben aber kein `pdf` in `capabilities.input`; das Modell antwortet, es könne keine PDF-Inhalte lesen |
| Größer als `MAX_ATTACHMENT_MB` | wird abgelehnt | nicht geladen, Hinweis in der Antwort |

Bildverständnis braucht ein **multimodales Modell**, das opencode auch als
solches kennt: `image` muss in `capabilities.input` des Modells stehen
(`GET /api/model`). Für Ollama liefert die Discovery das automatisch (z. B.
`gemma4:e4b`, `gemma3:4b`: `["text", "image"]`); fehlt es bei einem Modell,
lässt es sich in `infra/opencode/config/opencode.jsonc` unter
`providers.<provider>.models.<modell>` ergänzen. In opencode 2.0.20 heißt
das Feld `capabilities` (das ältere `modalities` gibt es dort nicht), z. B.
`"capabilities": { "tools": true, "input": ["text", "image"], "output": ["text"] }`.
Ohne `image` erreicht das Bild das Modell nicht, und es rät.

PDF/Office werden derzeit nicht ausgewertet. Vorschlag: Captain extrahiert
beim Laden den Text (PDF z. B. mit `pypdf`, Office mit `python-docx`/
`openpyxl` oder generisch per `markitdown`) und legt ihn als
`<name>.txt` daneben ins Session-Verzeichnis – dann greift der Weg für
Textdateien inklusive `read`. Gescannte PDFs bräuchten OCR bzw. ein Modell
mit `pdf`-Eingabe.

## opencode-Client

`captain/opencode.py` spricht die v2-API von `opencode serve`
(`@opencode/cli@2.0.20`, Basic-Auth-Benutzer `opencode`).

```python
with OpencodeClient(url, password) as oc:
    d = "/tmp/captain/ses_abc"                      # vorher anlegen!
    sid = oc.create_session(d, title="DM abc", session_id="ses_abc",
                            permissions=session_permissions(d))
    oc.set_instructions(sid, {"captain-persona": "Du bist …"})
    text = oc.prompt(sid, "Hallo", directory=d,
                     model="ollama/gemma4:e4b",
                     on_delta=lambda t: ..., on_status=lambda s: ...)
```

- **MCP:** opencode verbindet MCP-Server pro Verzeichnis erst beim ersten
  Zugriff; `prompt` ruft deshalb vorher `ensure_mcp(directory)` auf
  (`GET /api/mcp?location[directory]=…`, wartet auf `pending`-Server,
  höchstens 10 s). Ohne konfigurierte Server kostet das einmalig ≤ 3 s.
- **Verzeichnisse** sind Pfade *im opencode-Container* und müssen existieren,
  bevor gepromptet wird (sonst HTTP 500 → `OpencodeError`). Der Client legt
  sie nicht an; der Bot erzeugt sie im geteilten Volume `captain-tmp`.
- `create_session(directory, title=None, permissions=None, *, session_id=None)`:
  `permissions` sind Session-Regeln (`[{action, resource, effect}]`), die
  opencode hinter die globalen hängt (letzte passende gewinnt);
  `session_permissions(directory)` liefert die Captain-Regeln.
  `set_instructions(sid, {key: text})` setzt dauerhafte Systemanweisungen.
- **Event-Stream:** ein gemeinsamer SSE-Reader-Thread pro Client verteilt
  Events nach Session-ID an die laufenden `prompt`-Aufrufe (Registrierung
  vor dem Absenden, Reconnect mit Backoff). Nach einem Abriss oder bei Stille
  gleicht `prompt` per `GET /api/session/{id}/message` ab (die Ausführung ist
  fertig, sobald nach der eigenen User-Nachricht eine `idle`-Nachricht steht;
  ausgewertet wird nur bis zu diesem ersten `idle`) und holt den finalen Text
  von dort. Steht die eigene Nachricht schon in der Historie, gilt sie als
  zugestellt, auch wenn das Event dazu verpasst wurde.
- `on_delta` erhält immer den **gesamten** bisherigen Text; mehrere
  Textblöcke (Text → Tool → Text) werden mit Leerzeile verbunden.
  Denkspur (`reasoning`) wird nie weitergegeben.
- Fehler: `SessionGone` (404), `Interrupted` (abgebrochen, Unterklasse von
  `OpencodeError`), sonst `OpencodeError`.

## Test-Setup

Die lokalen Container und ihre Einrichtung sind in `infra/mattermost/README.md`
und `infra/opencode/README.md` beschrieben; gestartet wird alles über die
`compose.yml` im Wurzelverzeichnis.

## Entwicklung

```sh
python -m venv .venv
.venv/Scripts/python -m pip install -e ".[test]"   # Linux: .venv/bin/python
.venv/Scripts/python -m pytest                     # Unit-Tests
.venv/Scripts/python -m pytest -m integration      # gegen das laufende Test-Setup
```

Die Mattermost-Integrationstests lesen die Zugangsdaten aus der von
`infra/mattermost/seed.py` erzeugten env-Datei (Default
`C:/source/captain-shared/mattermost.env`, überschreibbar per
`CAPTAIN_MM_ENV`); fehlt sie, werden die Tests übersprungen.
Die opencode-Integrationstests lesen analog
`C:/source/captain-shared/opencode.env` (`CAPTAIN_OC_ENV`), sprechen
`OPENCODE_URL` (Default `http://localhost:4096`) an und arbeiten im
Container-Verzeichnis `CAPTAIN_OC_DIR` (Default `/tmp`). Die Restriktions-Tests
(`tests/test_restrict_integration.py`) legen Session-Verzeichnisse unter
`/tmp/captain` per `docker exec` im Container `CAPTAIN_OC_CONTAINER` (Default
`captain-opencode-1`) an. Mit einem lokalen CPU-Modell dauert der erste
Prompt nach Kaltstart ~2 min.

Python ≥ 3.11.
