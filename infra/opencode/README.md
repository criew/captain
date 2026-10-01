# opencode-Server (Captain)

opencode läuft headless als dauerhafter Server im Container `opencode` (Port `4096`)
und wird über das Root-`compose.yml` (`include:`) eingebunden. Dasselbe Image
nutzt der Betrieb (`compose.deploy.yml`, Tag `captain/opencode:2.0.20`, ohne
Host-Port; siehe Haupt-README).

## Schnellstart

```bash
cp infra/opencode/.env.example infra/opencode/.env   # Passwort setzen!
docker compose up -d opencode                        # aus dem Repo-Root
# nur dieser Service (ohne Mattermost-Compose):
docker compose -p captain -f infra/opencode/compose.yml up -d --build
```

Healthcheck: `GET /api/info` mit Basic-Auth; `docker compose ps` zeigt `healthy`.

## Version

**opencode v2, npm `@opencode/cli@2.0.20`** (fest gepinnt in `Dockerfile` und
`compose.yml`, Build-Arg `OPENCODE_VERSION`). Update-Prüfung ist per Config
abgeschaltet (`"update": "disable"`).

Begründung: v2 läuft headless im Server-Modus sauber (`opencode serve`), bringt eine
versionierte REST-API unter `/api/*` mit OpenAPI-Spec, ein typisiertes SSE-Event-Modell
mit Text-Deltas sowie automatische Ollama-Discovery. Alle Akzeptanztests (Session,
Prompt, SSE, Tools, Permissions, Interrupt) liefen gegen 2.0.20 erfolgreich. v1
(`opencode-ai` 1.18.x) wird nicht benötigt.

Achtung: v2-Konfiguration ist **nicht** v1-kompatibel (`providers` statt `provider`,
`permissions` als Regel-Array statt `permission`-Objekt, Paket
`@opencode/ai/providers/...` statt `@ai-sdk/...`).

## Dateien

| Datei | Zweck |
|---|---|
| `Dockerfile` | `node:22-bookworm-slim` + `@opencode/cli` (npm) + ripgrep (GitHub-Release, Prüfsumme) – ohne Paketmanager der Distribution |
| `compose.yml` | Test-Setup: Service `opencode`, Port 4096, Volumes `captain-tmp`, `opencode-data`, `/shared` (`CAPTAIN_SHARED_DIR` aus der Shell, sonst leeres Volume `shared-leer`) |
| `config/base.jsonc` | **Sicherheitsbasis** (fest im Image) → `/root/.config/opencode/opencode.jsonc` |
| `start.mjs` | Entrypoint: prüft die Admin-Config per Allowlist, schreibt die bereinigte Kopie `/run/captain/opencode.json` (= `OPENCODE_CONFIG`), setzt `OPENCODE_CONFIG_CONTENT` (inkl. webfetch- und external_directory-Policies)/Projekt-Config-Sperre fest, prüft `/shared`, reicht nur eine Umgebungs-Allowlist durch, startet `opencode serve` |
| `egress.mjs` | Egress-Filter (HTTP-Proxy auf 127.0.0.1 im Startskript), nur aktiv mit `CAPTAIN_WEBFETCH_ALLOW` – siehe „webfetch“ |
| `shared.mjs` | Geteiltes Verzeichnis `/shared` (`CAPTAIN_SHARED_DIR`): Pfadprüfung, Policies, Prüfung auf Symlinks u. a. beim Start und alle 10 s – siehe „Geteiltes Verzeichnis“ |
| `start.test.mjs`, `webfetch.test.mjs`, `shared.test.mjs` | Node-Unit-Tests (über `tests/test_start_script.py`) |
| `config/opencode.jsonc` | Vorlage der **Admin-Config** (Provider `llm`, MCP, Freigaben) → `/etc/captain/opencode.jsonc` (`OPENCODE_CONFIG`) und `/opt/captain/defaults/` |
| `config/opencode.test.jsonc` | Admin-Config des Test-Setups (Ollama), per `compose.yml` eingebunden |
| `config/AGENTS.md` | globaler Systemprompt „Captain“ → `/etc/captain/AGENTS.md` (Symlink von `/root/.config/opencode/AGENTS.md`) |
| `.env.example` | Vorlage für `.env` (gitignored) |
| `openapi.json` | OpenAPI-3.1-Spec der gepinnten Version (von `GET /openapi.json`) |

Die Sicherheitsbasis ist ins Image kopiert (Änderung → `up -d --build`); die
Admin-Config und `AGENTS.md` unter `/etc/captain` bindet der Betrieb aus
`$CAPTAIN_HOME/config` ein (Änderung → `restart opencode`).

## Config-Quellen (v2.0.20, aus dem Binary ermittelt)

opencode lädt nacheinander: global (`/root/.config/opencode/opencode.jsonc`) →
`OPENCODE_CONFIG` (bereinigte Kopie) → Projekt-Config (hier abgeschaltet) →
`OPENCODE_CONFIG_CONTENT`. Zusammengeführt wird je Schlüssel verschieden:

| Schlüssel | Zusammenführung | Risiko aus der Admin-Config |
|---|---|---|
| `plugins` | aus allen Quellen gesammelt (nur gezieltes Entfernen möglich) | JavaScript im Server als root |
| `mcp.servers` | pro Name, spätere Quelle gewinnt | `type: local` startet beliebige Prozesse als root |
| `agents` | pro Agent angewendet (Rechte, `system`, `model`, `disabled`) | Agenten umbauen |
| `commands`, `skills`, `references`, `instructions` | aus allen Quellen gesammelt | Befehle/Fähigkeiten/Dateien einschleusen |
| `shell`, `enterprise`, `default_agent`, `worktree` | letzte Quelle | Verhalten ändern |

Diese Schlüssel lassen sich aus einer späteren Quelle nicht sperren. Deshalb
übernimmt `start.mjs` aus der Admin-Config nur eine Allowlist (siehe
Haupt-README, Kapitel 7); verworfene Teile stehen als WARNUNG im Log.
Für die übrigen Schlüssel gilt:

| Schlüssel | Zusammenführung | Folge |
|---|---|---|
| `permissions` | aneinandergehängt in obiger Reihenfolge, danach Session-Regeln; letzte passende Regel gewinnt | Admin-Config kann nach dem globalen `* * deny` freigeben; Session-Regeln des Bots haben das letzte Wort |
| `experimental.policies` | gesammelt, bei gleichem Treffer gewinnt die **früheste** Quelle | Policies der Sicherheitsbasis sind durch keine spätere Quelle aufhebbar |
| `mcp.servers`, `providers` | pro Name gemischt, spätere Quelle gewinnt | Admin-Config definiert Server und Provider |
| Einzelwerte (`model`, `share`, `snapshots`, `websearch`, …) | letzte Quelle, die sie setzt | feste Schalter stehen deshalb in `OPENCODE_CONFIG_CONTENT` (von `start.mjs` gesetzt) |
| globale `AGENTS.md` | nur `<config-dir>/AGENTS.md` | per Symlink auf `/etc/captain/AGENTS.md` |

`{env:NAME}` ersetzt opencode textuell vor dem Parsen (auch in Schlüsseln,
**ohne Escaping** – ein Wert mit `"` könnte neue Schlüssel erzeugen), fehlende
Variablen werden zu `""`. Deshalb setzt `start.mjs` alle Platzhalter der
Admin-Config selbst JSON-sicher ein (nur `LLM_*`, `OLLAMA_*`, `MCP_*`,
`*_API_KEY`, `OPENCODE_MODEL`/`_VARIANT`) und gibt opencode eine Kopie ohne
Platzhalter. Provider-Pakete lädt opencode per `import`; ein `package`
außerhalb der eingebauten Liste (`file://…` oder npm-Name) würde lokal
importiert bzw. zur Laufzeit per npm installiert – `start.mjs` erlaubt nur
eingebaute Pakete. Ungültige Werte (z. B. leeres `model`) überspringt
opencode mit einer Warnung `configuration normalization diagnostic` im Log;
unlesbares JSON verwirft die ganze Datei. Die Sicherheitsbasis bleibt in
beiden Fällen.

Belegt durch `tests/test_config_layers_integration.py` (eigener Container,
Fake-LLM, Test-MCP-Server): Admin-Config mit `* * allow`, allow-Policies,
`share: auto` → Shell/Web/Code Mode bleiben unsichtbar; mit Session-Regel
`* * allow` sind sie sichtbar, Aufrufe enden mit „Blocked by configuration
policy“ bzw. „Permission denied: external_directory“.

## Volumes

- `captain-tmp` → `/tmp/captain`: Session-Verzeichnisse `/tmp/captain/<session-id>`,
  geteilt mit dem Bot-Container (Docker-Name `captain_captain-tmp`), übersteht
  Neustarts. Ersetzt das frühere Volume `workspaces` (kann per
  `docker volume rm captain_workspaces` entfernt werden).
- `opencode-data` → `/root/.local/share/opencode`: SQLite-DB (`opencode.db`) mit
  Sessions und Nachrichten.
- `shared-leer` → `/shared` (schreibgeschützt), solange `CAPTAIN_SHARED_DIR`
  leer ist; sonst das Host-Verzeichnis.

Der Server läuft als `root`; Dateien in `/tmp/captain` gehören also root.

## Auth

HTTP Basic-Auth, Benutzer **`opencode`**, Passwort aus `OPENCODE_SERVER_PASSWORD`
(`.env`). Ohne die Variable erzeugt opencode bei jedem Start ein Zufallspasswort und
schreibt es ins Log. Andere Benutzernamen werden abgelehnt (401).

## Modell / Provider

Default: **lokales Ollama auf dem Docker-Host**, Modell `ollama/gemma4:e4b`
(`OPENCODE_MODEL` in `.env`; die Test-Admin-Config `config/opencode.test.jsonc`
liest es über `"model": "{env:OPENCODE_MODEL}"`). Im Betrieb ist der Standard
stattdessen der generische OpenAI-kompatible Provider `llm` (`LLM_BASE_URL`,
`LLM_API_KEY`, `LLM_MODEL`; Paket `@ai-sdk/openai-compatible`, intern
`@opencode/ai/providers/openai-compatible`), über den auch Ollama geht.
Kein API-Key nötig.

- Endpoint: `OLLAMA_BASE_URL=http://host.docker.internal:11434/v1`
  (`extra_hosts: host.docker.internal:host-gateway`). opencode v2 entdeckt alle
  Ollama-Modelle automatisch über die native API am selben Präfix; Embedding-Modelle
  werden ausgeblendet. Liste: `GET /api/model`.
- Getestet: `gemma4:e4b` ruft Tools zuverlässig auf (write-Tool + Antwort).
  `qwen3:4b` hat sich im Test beim Tool-Aufruf minutenlang im Reasoning verloren
  (abgebrochen nach > 8 min), ist für einfache Antworten aber okay.
- **Kontextlänge:** Ollama lädt Modelle mit seiner eigenen `num_ctx` (auf diesem Host
  16384, sichtbar in `ollama ps` bzw. `GET http://localhost:11434/api/ps`), die
  OpenAI-kompatible API kann sie pro Request nicht ändern. Der opencode-Systemprompt
  inkl. Tools hat ~6k Token. Damit opencode rechtzeitig kompaktiert, ist das Limit in
  `config/opencode.test.jsonc` für `qwen3:4b` und `gemma4:e4b` auf 16384 gesetzt. Wer mehr
  Kontext will: auf dem Host `OLLAMA_CONTEXT_LENGTH` erhöhen (Ollama neu starten) und
  die `limit.context`-Werte angleichen.
- **Performance:** Ollama läuft auf diesem Host ohne GPU (`size_vram: 0`). Die erste
  Antwort braucht dadurch ~2 min (Prompt-Verarbeitung von ~6k Token), Folgeprompts
  profitieren vom Cache.

### Alternativen (optional)

- **opencode Zen Free** (Cloud, ohne Key, `apiKey: public`): z. B.
  `OPENCODE_MODEL=opencode/big-pickle` – im Test Antwort inkl. Shell-Tool in ~4 s.
  Weitere `*-free`-Modelle über `GET /api/model`. Nutzung/Datenweitergabe laut
  opencode-Zen-Bedingungen; Verfügbarkeit ändert sich.
- **API-Keys** in `.env` setzen (werden per `env_file` durchgereicht):
  `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OPENROUTER_API_KEY`, `OPENCODE_API_KEY`
  (Zen/Go), dann `OPENCODE_MODEL` z. B. `anthropic/claude-sonnet-4-5`.
- Modell pro Session: im Body von `POST /api/session` `"model":{"providerID":"ollama","id":"qwen3:4b"}`
  oder später `POST /api/session/{id}/model`.

## Berechtigungen (restriktiv, headless)

Ziel: **keine Tools außer Dateizugriff im eigenen Session-Verzeichnis**
`/tmp/captain/<session-id>`, und nichts darf an einer Rückfrage (`ask`) hängen.

### Auswertung in v2.0.20 (aus dem Binary ermittelt und getestet)

- Regeln: `{action, resource, effect}` mit `effect` ∈ `allow|deny|ask`; `*` im
  Muster passt auf alles inkl. `/`.
- Reihenfolge: **Agent-Defaults → globale `permissions` → Session-`permissions`**
  (Feld `permissions` bei `POST /api/session`). Die **letzte** passende Regel
  gewinnt; Session-Regeln können globale also überstimmen. Ohne Treffer gilt
  `ask` (würde headless hängen) → global zuerst `* * deny`.
- Tool-Sichtbarkeit: Ist die letzte passende Regel einer Aktion
  `resource: "*", effect: "deny"`, bietet opencode das Tool dem Modell **gar
  nicht erst an**. Eine eigene `tools: {bash: false}`-Option gibt es in v2
  nicht (Agent-Config kennt nur `permissions`); das Verbot per Regel erfüllt
  denselben Zweck.
- `experimental.policies` (`{action: "permission", resource: "<aktion>:<ressource>",
  effect: "deny"}`) ist eine harte Obergrenze nach den Regeln – auch eine
  Session-Regel `* * allow` kann sie nicht aufheben (Fehler „Blocked by
  configuration policy“).
- Aktionsnamen: `read`, `edit` (Tools edit, write, apply_patch), `glob`, `grep`
  (Ressource = Suchmuster), `shell`, `webfetch`, `websearch`, `subagent`,
  `skill`, `question`, `external_directory`, `execute` (Code Mode),
  `opencode_list_mcp_resources`, `opencode_read_mcp_resource` sowie MCP-Tools
  als `<server>_<tool>` (Ressource `*`).
- **Code Mode** (`execute`): ein Skript-Tool (eingeschränktes JavaScript mit
  eigenem `fetch`!), über das opencode MCP-Tools mit `codemode` ≠ `false`
  gebündelt anbietet. Es läuft **nicht** über den Policy-Hook (getestet: mit
  freigegebener Regel liefert `execute` Daten per `fetch`, obwohl `webfetch`
  per Policy gesperrt ist). Gesperrt ist es deshalb über Regeln: global
  `* * deny` und die Session-Regeln des Bots (siehe unten). `start.mjs` setzt
  für MCP-Server immer `"codemode": false`. `list`, `todo`, `lsp` gibt es
  als Aktion nicht.
- Pfade: Innerhalb des Session-Verzeichnisses prüft opencode `read`/`edit` mit
  **relativem** Pfad (`notiz.txt`). Pfade außerhalb (auch `../x`) laufen vorher
  über `external_directory` mit Ressource `<verzeichnis>/*` und danach absolut.
  Die Agent-Defaults (erste Stufe) erlauben u. a. `external_directory` für
  `/tmp/opencode/*` und `/root/.config/opencode/*` und `*` für alles; weil die
  globale Regel `* * deny` **danach** kommt, sind diese Defaults vollständig
  aufgehoben (sichtbar in `GET /api/agent`: Defaults zuerst, Config-Regeln
  dahinter).

### Sicherheitsbasis (`config/base.jsonc`)

```jsonc
"permissions": [ { "action": "*", "resource": "*", "effect": "deny" } ],
"experimental": { "policies": [   // harte Obergrenze
  { "action": "permission", "resource": "shell:*",              "effect": "deny" },
  { "action": "permission", "resource": "websearch:*",          "effect": "deny" },
  { "action": "permission", "resource": "subagent:*",           "effect": "deny" },
  { "action": "permission", "resource": "skill:*",              "effect": "deny" },
  { "action": "permission", "resource": "question:*",           "effect": "deny" },
  { "action": "permission", "resource": "opencode_*",           "effect": "deny" }
] }
```

Dazu setzt `start.mjs` `OPENCODE_CONFIG_CONTENT` mit `share: disabled`,
`update: disable`, `websearch: false`, `lsp: false`, `formatter: false`,
`snapshots: false` (letzte Quelle → Admin-Config kann sie nicht ändern) und
den webfetch-Policies (`webfetch:*` deny, mit Allowlist danach die erlaubten
Muster – siehe „webfetch“) sowie `external_directory:*` deny (mit
`CAPTAIN_SHARED_DIR` danach `/shared/*` erlaubt, `.git` und `edit` dort
verboten – siehe „Geteiltes Verzeichnis“). Eine solche Policy in der Basis würde
jede Freigabe schlagen (früheste Quelle gewinnt), deshalb stehen beide dort
nicht; die Admin-Config darf `experimental` nicht setzen.

### Pro Session (Bot, `captain.opencode.session_permissions`)

```json
[
  {"action": "read",  "resource": "*", "effect": "allow"},
  {"action": "edit",  "resource": "*", "effect": "allow"},
  {"action": "glob",  "resource": "*", "effect": "allow"},
  {"action": "grep",  "resource": "*", "effect": "allow"},
  {"action": "read",  "resource": "/*", "effect": "deny"},
  {"action": "read",  "resource": "/tmp/captain/<id>/*", "effect": "allow"},
  {"action": "edit",  "resource": "/*", "effect": "deny"},
  {"action": "edit",  "resource": "/tmp/captain/<id>/*", "effect": "allow"},
  // für read und edit, je Name N aus .opencode, .claude, .agents,
  // opencode.json*, AGENTS.md, CLAUDE.md: N, N/*, */N, */N/* → deny
  // zum Schluss (überstimmt auch Freigaben der Admin-Config):
  // shell, webfetch, websearch, subagent, skill, question, execute,
  // opencode_*, external_directory → {"resource": "*", "effect": "deny"}
  // nur mit CAPTAIN_WEBFETCH_ALLOW, je Präfix p:
  // {"action": "webfetch", "resource": p | p + "/*", "effect": "allow"},
  // danach webfetch deny für "*/..", "*/..?*", "*/%2e*", … und Tab/LF/CR
  // nur mit CAPTAIN_SHARED_DIR (captain.shared.session_rules):
  // {"action": "external_directory", "resource": "/shared/*", "effect": "allow"},
  // {"action": "read", "resource": "/shared" | "/shared/*", "effect": "allow"},
  // external_directory und read für /shared/.git, /shared/.git/*, /shared/*/.git,
  // /shared/*/.git/* → deny; edit für /shared, /shared/* → deny
]
```

### Geteiltes Verzeichnis (`CAPTAIN_SHARED_DIR`)

Betrieb, Risiken und Admin-Schritte: Haupt-README, Kapitel 10. Technisch
(aus dem Binary, `FileAccess.resolve`/`authorizeRead`, belegt durch
`tests/test_shared_integration.py`):

- Pfade werden mit `path.resolve(<session-verzeichnis>, pfad)` **lexikalisch**
  aufgelöst, kein `realpath`; innerhalb des Session-Verzeichnisses (oder des
  Projekts, das hier das Session-Verzeichnis ist) geht `read` relativ weiter.
- Außerhalb: `external_directory` mit Ressource `<verzeichnis>/*` (Datei:
  Elternverzeichnis per `stat`, folgt Symlinks), danach `read`/`edit` mit dem
  absoluten Pfad. `/shared` selbst ergibt `/shared/*`, `/sharedX/a` ergibt
  `/sharedX/*` (passt nicht auf `/shared/*`), `/shared/../etc/x` ergibt `/etc/*`.
- `glob`/`grep`: Ressource ist das Suchmuster, der Suchpfad läuft über
  `external_directory`. ripgrep läuft ohne `--follow` mit `--glob=!**/.git/**`
  (zuletzt, gewinnt gegen `include`).
- **Symlinks** unter `/shared` würden gelesen (Gegenprobe im Test: fremde
  Session-Datei, `/etc/passwd`). Deshalb prüft `shared.mjs` beim Start und alle
  10 s alles unter `/shared` per `lstat` (Symlinks, `nlink > 1`, Geräte/FIFOs/
  Sockets, Verzeichnisse mit derselben Geräte-/Inode-Nummer wie
  `/tmp/captain`, `/root/.local/share/opencode`, `/etc/captain`, `/run/captain`,
  `/root/.config/opencode`) – mit Befund startet opencode nicht bzw. wird
  beendet. `/shared` muss außerdem schreibgeschützt sein.
- Ohne Variable hängt an `/shared` ein leeres Volume, und die Policy
  `external_directory:*` deny gilt ohne Ausnahme.

### webfetch (Allowlist `CAPTAIN_WEBFETCH_ALLOW`)

Ermittelt aus dem Binary (Tool `opencode.tool.webfetch`, Matcher, Policy-Hook)
und belegt durch `tests/test_webfetch_integration.py` (Fake-LLM, Testserver
mit mehreren Hostnamen):

- Geprüft wird `assert({action: "webfetch", resources: [url]})` mit der
  **rohen URL** des Modells (vorher nur `new URL(url)` und Schema http/https),
  in Policies als `webfetch:<url>`.
- Matcher: `\` im Wert → `/`, im Muster `*` → `.*` (auch `/`, Zeilenumbrüche),
  `?` → **ein beliebiges Zeichen**, sonst wörtlich; Groß-/Kleinschreibung
  zählt. `http://text-example.org/*` trifft also nicht
  `http://text-example.org.evil.com/…`, `…@evil.com`, `https://…`, `…:8080`,
  `HTTP://TEXT-EXAMPLE.ORG/…` und nicht `http://text-example.org` ohne `/` –
  deshalb erlaubt ein Eintrag `p` und `p/*`. Query-Strings hinter `/` passen.
  Ein Muster `p?*` wäre gefährlich (`?` passt auf `.` → `p.evil.com`) und
  kommt nicht vor.
- Ein `\` hinter dem Host (`http://text-example.org\@evil.com/`) passt auf
  `p/*`, bleibt beim Abruf aber beim erlaubten Host (der URL-Parser liest `\`
  ebenfalls als `/`) – getestet.
- Der URL-Parser löst `..` (auch `%2e%2e`) auf und entfernt Tab/LF/CR –
  deshalb verbieten Regeln und Policies das nach den Freigaben (sonst käme
  man aus einem Pfad-Präfix heraus).
- **Weiterleitungen**: opencode ruft per `fetch` mit `redirect: follow` ab und
  prüft das Ziel **nicht** erneut (getestet: Weiterleitung von
  `text-example.org` auf `evil.test` lieferte ohne Filter den fremden Inhalt).
  Absicherung: Egress-Filter `egress.mjs`. Mit Allowlist setzt `start.mjs` für
  opencode `HTTP(S)_PROXY=http://127.0.0.1:<port>`, `NO_PROXY` nur Loopback; der
  Filter lässt nur Host:Port der Allowlist (bei Standardport auch 80/443
  desselben Hosts) und die Endpunkte aus der bereinigten Admin-Config
  (`providers.*.settings.baseURL`, `mcp.servers.*.url`), `LLM_BASE_URL`,
  `OLLAMA_BASE_URL` und Cloud-APIs mit gesetztem Key durch, sonst `403` und
  `[captain-egress] blockiert: host:port` im Log. Weiter geht es direkt bzw.
  über den ursprünglichen Proxy (`HTTP(S)_PROXY`/`NO_PROXY` aus
  `CAPTAIN_*`). Bun sendet bei HTTPS über Proxy nur `CONNECT host:port` (ohne
  User-Agent) – eine Unterscheidung „nur webfetch filtern“ ist deshalb nicht
  möglich, der Filter gilt für den ganzen Prozess.
- Ohne Allowlist: kein Filter, Policy `webfetch:*` deny, Session-Regel
  `webfetch * deny` → Tool unsichtbar, wie bisher.

### Projekt-Config im Session-Verzeichnis (Sicherheit)

opencode sucht ab `location.directory` aufwärts nach `opencode.json(c)`,
`.opencode/opencode.json(c)`, `.opencode/plugins/*` (JS/TS, läuft **als root im
Server**), `.claude/`, `.agents/` und `AGENTS.md`. Da die Session dort schreiben
darf, wäre das ein Ausbruch. Nachgewiesen ohne Schutz: eine abgelegte
`.opencode/plugins/x.ts` wird ausgeführt, eine abgelegte `opencode.jsonc` mit
`* * allow` blendet shell/webfetch/… wieder ein (Policies halten die Aufrufe
zwar auf, das Plugin läuft aber schon), `AGENTS.md` landet im Systemprompt.

Schutz:

1. `OPENCODE_DISABLE_PROJECT_CONFIG=1` im Image (2.0.20 wertet
   `OPENCODE_CONFIG_PROJECT_DISABLE ?? OPENCODE_DISABLE_PROJECT_CONFIG` aus).
   Damit lädt opencode aus dem Projektverzeichnis weder Config noch Plugins noch
   `.claude`/`.agents`/`AGENTS.md` (getestet: Plugin-Marker bleibt aus, keine
   zusätzlichen Tools, `AGENTS.md` nicht im Systemprompt).
2. Zusätzlich verbieten die Session-Regeln `read`/`edit` auf genau diese Namen.

### MCP-Server

MCP-Tools heißen `<server>_<tool>` (Aktion, Ressource `*`) und fallen nicht
unter die Policy `opencode_*` (außer ein Server heißt `opencode…`). Server
entstehen nur über die Admin-Config (Projekt-Config ist aus); freigegeben wird
jedes Tool einzeln per Regel in der Admin-Config, alles andere bleibt durch
`* * deny` unsichtbar. Nur Remote-Server (HTTP); das Image enthält keine
Laufzeiten für stdio-Server.

opencode verbindet MCP-Server **pro Location** (Session-Verzeichnis) erst beim
ersten Zugriff und baut die Tool-Liste des ersten Prompts, bevor die
Verbindung steht. Der Client stößt deshalb vor jedem Prompt
`GET /api/mcp?location[directory]=<dir>` an und wartet, bis kein Server mehr
`pending` ist (`OpencodeClient.ensure_mcp`).

mit `location.directory = /tmp/captain/<id>` und `id = <id>` (eigene
Session-ID, Muster `^ses`, wird von v2 akzeptiert).

### Nachweis (gemma4:e4b, Tool-Liste per Logging-Proxy vor Ollama mitgeschnitten)

| Session | Tools, die das Modell sieht |
|---|---|
| Captain-Regeln | `read`, `write`, `edit`, `glob`, `grep` |
| ohne Session-Regeln | keine |
| Session-Regel `* * allow` (Gegenprobe) | zusätzlich `shell`, `webfetch`, `subagent`, `skill`, `question`, `execute` – Aufrufe scheitern an den Policies |

- „Führe `ls /` aus“ → kein Tool-Aufruf, Antwort „kein Shell-Tool verfügbar“.
- `/etc/passwd`, `/tmp/captain/<andere>/x.txt`, `../<andere>/x.txt` →
  `permission.rejected` („Permission denied: external_directory“), Antwort folgt.
- `notiz.txt` schreiben und lesen → liegt in `/tmp/captain/<id>/`.
- `glob`/`grep` mit Pfad `/etc` bzw. `/tmp/captain` → `external_directory` abgelehnt.
- Modell soll `.opencode/opencode.jsonc` und `.opencode/plugins/x.ts` schreiben → abgelehnt.
- Per Test direkt abgelegte Projekt-Config/Plugin/`AGENTS.md` (vor und während
  der Session) → keine Wirkung.
- Keine Rückfrage, `GET /api/session/{id}/permission` bleibt leer.

Automatisiert: `tests/test_restrict_integration.py` (`pytest -m integration`).

### Systemanweisungen pro Session

`PUT /api/experimental/session/{id}/instructions/entries/{key}` mit
`{"value": "<text>"}` hängt `<context key="…">…</context>` an den
Systemprompt (nach `AGENTS.md`), dauerhaft pro Session – übersteht also auch
Kompaktierung. Der Bot setzt `captain-persona` und `captain-umgebung`.

## API (v2.0.20)

Basis `http://localhost:4096` (im Compose-Netz `http://opencode:4096`), alle Pfade mit
Basic-Auth. Antworten sind in `{"data": ...}` gekapselt. Vollständig: `openapi.json`
(live: `GET /openapi.json`).

| Zweck | Methode + Pfad | Body / Hinweise |
|---|---|---|
| Server-Info / Health | `GET /api/info` | `{"version":"2.0.20",...}` |
| Session anlegen | `POST /api/session` | `{"id":"ses_…","title":"…","location":{"directory":"/tmp/captain/x"},"permissions":[…],"model":{"providerID":"…","id":"…"}}` (alles optional; `id` muss mit `ses` beginnen) → `data.id` (`ses_…`) |
| Session lesen/löschen | `GET` / `DELETE /api/session/{id}` | |
| Prompt senden | `POST /api/session/{id}/prompt` | `{"text":"…","delivery":"steer"\|"queue"}` → kehrt sofort zurück (`data.type:"user"`), Ausführung asynchron |
| Abbrechen | `POST /api/session/{id}/interrupt` | → `{"interrupted":true\|false}` |
| Nachrichten | `GET /api/session/{id}/message` | neueste zuerst, Cursor-Paginierung; Assistent: `type:"assistant"`, `content[]` mit `text`/`reasoning`/`tool` |
| Aktive Sessions | `GET /api/session/active` | |
| Event-Stream (SSE) | `GET /api/event` | alle Sessions/Locations, nach `data.sessionID` filtern |
| Modelle | `GET /api/model`, `GET /api/model/default` | optional `?location[directory]=…`; direkt nach Start kann `default` kurz `null` sein (Discovery läuft asynchron) |
| Modell wechseln | `POST /api/session/{id}/model` | |

Wichtig:

- **Das Verzeichnis muss existieren**, bevor ein Prompt gesendet wird. `POST /api/session`
  akzeptiert auch nicht existierende Pfade, `…/prompt` antwortet dann mit HTTP 500
  (`ENOENT … realPath`). Der Bot legt das Verzeichnis im geteilten Volume selbst an.
- `delivery:"steer"` (Default) speist eine Nachricht in eine laufende Ausführung ein,
  `"queue"` stellt sie hinten an.

### SSE-Format

Nur `data:`-Zeilen (kein `event:`-Feld), dazwischen `: heartbeat`. Jede Zeile ist JSON:

```json
{"id":"evt_…","created":1790786336546,"type":"session.text.delta",
 "location":{"directory":"/tmp/captain/test"},
 "data":{"sessionID":"ses_…","assistantMessageID":"msg_…","ordinal":0,"delta":"Hallo"}}
```

Relevante `type`-Werte (beobachtet):

| Event | `data` |
|---|---|
| `server.connected` | – (erste Zeile) |
| `session.created` | `sessionID`, `location`, `title` |
| `session.inbox.enqueued` / `.delivered` | Prompt angenommen / an Agent übergeben |
| `session.execution.started` | Ausführung beginnt |
| `session.step.started` / `.streamed` / `.ended` | ein LLM-Schritt; `ended` mit `finish`, `tokens`, `cost` |
| `session.reasoning.started` / `.delta` / `.ended` | Denkspur (nicht an Nutzer senden) |
| `session.text.started` / **`.delta`** / `.ended` | Antworttext; `delta` = Textstück, `ended.text` = kompletter Block; `ordinal` nummeriert Blöcke je Nachricht |
| `session.tool.input.started` / `.ended`, `session.tool.called`, `.progress`, `.success`, `.failed` | Tool-Aufrufe (`name`, `input`, `content` bzw. `error`) |
| `session.usage.updated` | Token-/Kostenstand |
| **`session.execution.succeeded`** / `.failed` / `.interrupted` | Ende der Ausführung |

Weitere Typen im Binary (nicht alle beobachtet): `session.error`, `session.idle`,
`session.step.failed`, `session.retry.scheduled`, `session.compaction.*`,
`session.permission`, `session.updated`, `session.deleted`, sowie globale
`*.updated`-Events (provider, model, agent, …).

### curl-Beispiel

```bash
PW=…   # OPENCODE_SERVER_PASSWORD
B=http://localhost:4096
docker compose exec opencode mkdir -p /tmp/captain/test   # Git Bash: MSYS_NO_PATHCONV=1 voranstellen

# Event-Stream in zweitem Terminal
curl -N -u opencode:$PW $B/api/event

# Session anlegen
SID=$(curl -s -u opencode:$PW -H 'content-type: application/json' \
  -d '{"title":"test","location":{"directory":"/tmp/captain/test"}}' \
  $B/api/session | python -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')

# Prompt senden (asynchron)
curl -s -u opencode:$PW -H 'content-type: application/json' \
  -d '{"text":"Sag Hallo"}' $B/api/session/$SID/prompt

# Antwort abholen (nach session.execution.succeeded)
curl -s -u opencode:$PW $B/api/session/$SID/message

# Abbrechen
curl -s -u opencode:$PW -X POST $B/api/session/$SID/interrupt
```

Ergebnis im Test (gemma4:e4b): Antwort `Hallo! Wie kann ich dir helfen?` in 4
`session.text.delta`-Events (~7 s mit warmem Cache); bei „Lege hallo.txt an und nenne drei
Städte“ `write`-Tool + 18 `session.text.delta`-Events.
