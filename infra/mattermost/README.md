# Mattermost-Testumgebung

Lokales Mattermost **9.11.15** (Team Edition) mit Postgres 15 für das
Captain-Test-Setup. Nur für Tests – alle Passwörter sind absichtlich trivial.

- Web/API: <http://localhost:8065>
- Compose-Projekt `captain`, Services `postgres` und `mattermost`
- Eingebunden über das Root-`compose.yml` (`include:`)

Server-Einstellungen per Env (siehe `compose.yml`): `SiteURL=http://localhost:8065`,
Bot-Accounts und Personal Access Tokens an, Rate-Limit aus, Local Mode an
(für `mmctl --local`), keine Mails/Telemetrie.

## Starten

```bash
# aus dem Repo-Root
docker compose up -d mattermost
# nur diese Umgebung (ohne die übrigen Includes)
docker compose -p captain -f infra/mattermost/compose.yml up -d
```

Der erste Start dauert (Image-Pull, DB-Migration); bereit, sobald
`curl -s http://localhost:8065/api/v4/system/ping` `"status":"OK"` liefert.

## Seeden

```bash
python infra/mattermost/seed.py      # optional: --url http://host:8065 --out datei.env
python infra/mattermost/verify.py    # Nachweis per REST
```

`seed.py` braucht nur Python 3 (stdlib) und spricht ausschließlich REST – läuft
also vom Host (Git Bash, Linux, macOS) ebenso wie aus einem Container. Es ist
idempotent: Vorhandenes wird wiederverwendet, gültige Tokens aus einer
bestehenden `generated.env` werden weiterbenutzt (keine Token-Flut).

Angelegt wird:

| Was | Details |
|---|---|
| Admin | `admin` / `Admin-Test-1234` (System-Admin, erster Nutzer) |
| Nutzer | `alice` / `Alice-Test-1234`, `bob` / `Bob-Test-1234`, `carol` / `Carol-Test-1234` |
| Team | `captain` (alle Nutzer + Bot) |
| Kanal öffentlich | `allgemein` (admin, alice, bob, carol, Bot) |
| Kanal privat | `familie` (alice, bob, Bot) |
| Gruppen-DM | alice + bob + carol + Bot |
| DM | alice ↔ Bot |
| Bot | `captain` („Captain“), Besitzer `admin`, mit Access Token |

Ergebnis: `infra/mattermost/generated.env` (gitignored) mit u. a.

```
MM_URL, MM_TEAM_ID, MM_TEAM_NAME
MM_BOT_USERNAME, MM_BOT_USER_ID, MM_BOT_TOKEN
MM_CHANNEL_ALLGEMEIN_ID, MM_CHANNEL_FAMILIE_ID, MM_GROUP_DM_ID, MM_DM_ALICE_BOT_ID
MM_ADMIN_{USERNAME,PASSWORD,USER_ID,TOKEN}
MM_ALICE_… / MM_BOB_… / MM_CAROL_…  {USERNAME,PASSWORD,USER_ID,TOKEN}
```

`verify.py` prüft, dass der Bot Mitglied aller Test-Kanäle (inkl. Gruppen-DM
und DM) ist, lässt alice in jeden Kanal posten und liest die Posts per
`GET /api/v4/channels/{id}/posts` mit dem Bot-Token.

> **Achtung:** Läuft der Bot (`docker compose up -d captain`), beantwortet er
> die `verify …`-Posts von alice in allen vier Kanälen – mit einem lokalen
> CPU-Modell dauert das Minuten und füllt die Sessions. `verify.py` also vor
> dem Bot-Start ausführen oder den Bot vorher stoppen
> (`docker compose stop captain`).

### mmctl

Local Mode ist aktiv, mmctl im Container also ohne Login nutzbar:

```bash
docker compose -p captain -f infra/mattermost/compose.yml exec mattermost mmctl --local user list
```

(`mmctl bot create` unterstützt `--local` nicht – deshalb legt `seed.py` den Bot
per REST mit Admin-Token an.)

## Zurücksetzen

Nur die eigenen Services und Volumes entfernen (andere Services im Projekt
`captain`, z. B. opencode, bleiben unberührt):

```bash
docker compose -p captain -f infra/mattermost/compose.yml rm -sf mattermost postgres
docker volume rm captain_mm-postgres captain_mm-config captain_mm-data \
  captain_mm-logs captain_mm-plugins captain_mm-client-plugins
rm -f infra/mattermost/generated.env
```

Danach wie oben starten und seeden. (Kein `down -v`/`--remove-orphans` auf das
ganze Projekt, sonst trifft es auch die Nachbar-Services.)
