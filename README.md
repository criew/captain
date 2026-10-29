# Captain

Chat-Assistent auf Basis von **opencode** (Server-Modus) mit eigenem
OpenAI-kompatiblem Modell (vLLM, Open WebUI, …). Captain hängt an
**Mattermost** und an einer **REST-Schnittstelle** und schreibt Antworten live
zurück. Jede Unterhaltung hat eine eigene, dauerhafte opencode-Session.

Betrieb, Konfiguration und Sicherheitsdetails: **[docs/betrieb.md](docs/betrieb.md)**.
Anwendungsbeispiel mit Mattermost-Chat und GitLab-MR-Review über n8n:
**[docs/anwendungsbeispiel](docs/anwendungsbeispiel/README.md)**.

## Was Captain kann

- **Mattermost:** In DMs antwortet er auf alles, in Kanälen und Gruppen nur auf
  `@captain` – dann im Thread, wo er ohne Erwähnung weiter mitredet. Die
  Antwort wächst live in *einem* Post, Tool-Aufrufe erscheinen als Statuszeile.
- **REST:** `POST /api/conversations/{id}/messages` – gleiche `id` = gleiche
  Session. Synchron oder mit `callback_url` asynchron, z. B. für
  GitLab-MR → n8n → Review, das der Agent selbst in den MR postet. Optional `"agent": "reviewer"` aus
  `CAPTAIN_REST_AGENTS` mit eigenem Prompt und eigenen MCP-Tools.
- **Warteschlange:** pro Session eine Ausführung; was währenddessen kommt,
  bekommt ⏳ und wird gebündelt beantwortet.
- **Nachholen:** Nach Neustart oder Verbindungsabriss beantwortet er verpasste
  Nachrichten (bis `CATCHUP_MAX_AGE`), nie doppelt.
- **Aufräumen:** Sessions, die `SESSION_MAX_IDLE` (14 Tage) ruhen, werden
  gelöscht; Threads und DMs machen danach mit dem Chat-Verlauf als Kontext
  weiter.
- **Anhänge** landen im Session-Verzeichnis; Text liest jedes Modell, Bilder
  nur multimodale. PDF/Office (noch) nicht.
- **Werkzeuge:** nur Dateien im eigenen Session-Verzeichnis (`read`, `write`,
  `edit`, `glob`, `grep`). Optional: freigegebene MCP-Tools, `webfetch` für
  feste URL-Präfixe, ein geteiltes Verzeichnis `/shared` nur lesend.
- **Keine Shell, kein freies Web, keine Subagenten** – mehrfach abgesichert
  (Sicherheitsbasis im Image, bereinigte Admin-Config, Session-Regeln,
  Egress-Filter), belegt durch Integrationstests.
- **Kanal-Hinweis pro Session** (`captain-kanal`): Mattermost-Markdown und
  `Name: Text` im Chat, im REST-Kanal schlichtes CommonMark ohne Floskeln.

| Befehl (Mattermost) | Wirkung |
|---|---|
| `!neu` | neue Session (Verlauf vergessen) |
| `!modell [provider/modell [variante]]` | Modelle zeigen bzw. setzen; `standard` setzt zurück |
| `!stopp` | laufende Antwort abbrechen, Wartende verwerfen |
| `!help` | Hilfe |

## REST in 10 Sekunden

```sh
curl -s -H "Authorization: Bearer $CAPTAIN_API_TOKEN" \
     -d '{"text": "Starte ein Review von MR !7", "sender": "GitLab"}' \
     http://localhost:8090/api/conversations/gitlab:42:mr-7/messages
# {"conversation":"gitlab:42:mr-7","session":"ses_…","answer":"…"}
```

Mit `"callback_url": "https://n8n…/webhook/…"` kommt sofort `202`, die Antwort
später per POST. `DELETE /api/conversations/{id}` vergisst die Unterhaltung.
Details: [docs/betrieb.md#rest-eingabekanal](docs/betrieb.md#rest-eingabekanal).

## Schnellstart (Betrieb)

```sh
git clone <repo-url> /opt/captain-src && cd /opt/captain-src
cp .env.example .env && chmod 600 .env && $EDITOR .env   # MM_*, LLM_*, Passwort, UID/GID
docker compose -f compose.deploy.yml up -d --build
docker compose -f compose.deploy.yml logs -f captain     # „Captain läuft als @captain (…)“
```

## Aufbau

```
compose.deploy.yml   Betrieb: opencode + Bot gegen ein bestehendes Mattermost
compose.yml          Test-Setup mit eigenem Mattermost, Profil review: GitLab + n8n (infra/*)
infra/opencode/      opencode-Image: Sicherheitsbasis, Startskript, Config-Vorlagen
src/main/java/…      Bot (Spring Boot 4, Java 21): mattermost/, opencode/, bot/, api/, policy/
docs/betrieb.md      Betrieb, Konfiguration, Sicherheit, Entwicklung
docs/anwendungsbeispiel/  Beispiel: Chat + GitLab-MR-Review (n8n), mit Diagrammen
```

## Entwicklung

```sh
./gradlew test               # Unit- und Node-Tests
./gradlew integrationTest    # gegen Docker bzw. laufendes Test-Setup
docker compose up -d mattermost opencode && python infra/mattermost/seed.py
docker compose up -d --build captain && python scripts/e2e.py
docker compose --profile review up -d   # GitLab + n8n, siehe docs/betrieb.md
```
