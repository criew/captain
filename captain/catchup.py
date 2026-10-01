"""Verpasste Posts nachholen – reine Hilfsfunktionen (ohne Netz testbar).

Ziel: Nach einem Neustart oder einer WebSocket-Lücke geht keine an Captain
gerichtete Nachricht verloren, und keine wird doppelt beantwortet.

Ablauf (siehe :meth:`captain.bot.Bot.catch_up`)
------------------------------------------------
1. Der WebSocket verbindet zuerst; ab dem ``hello`` einer *neuen* Verbindung
   (Start oder Reconnect ohne Replay) puffert der Bot alle Live-Posts.
2. Für jeden Kanal des Bots (Team-Kanäle, DMs, Gruppen-DMs; übersprungen,
   wenn ``last_post_at`` nicht nach dem Cursor liegt) werden die Posts seit
   dem Cursor per REST geladen – höchstens :attr:`Config.catchup_max_age`
   alt –, über alle Kanäle chronologisch sortiert und durch dieselbe
   Entscheidungslogik geschickt wie Live-Posts (``Bot.addressed`` →
   ``should_answer``). Übersprungen wird, was schon beantwortet ist:
   gespeicherte Post-IDs, dazu die IDs, die Captains fertige Antworten in
   ``props.captain_answers`` tragen (:func:`answered_in` – deckt auch einen
   Absturz ab, bevor der Zustand gespeichert war). Antworten bekommen
   :data:`LATE_NOTE`.
3. Danach wird der Puffer abgearbeitet. Überschneidungen fallen über die
   Post-ID heraus. So entsteht keine Lücke zwischen Nachholen und Live-Stream.
4. Scheitert das Nachholen (ganz oder für einzelne Kanäle), bleiben die
   betroffenen Cursor eingefroren, Live-Posts laufen weiter, und das Nachholen
   wird mit Backoff wiederholt.

Erster Start (keine Cursor-Datei): alle Kanäle auf „jetzt“, nichts nachholen.
Kanal ohne Cursor (neu beigetreten): ab Beitritt, siehe :func:`start_for_new`.
"""

from __future__ import annotations

LATE_NOTE = "_(verspätet – ich war kurz offline)_"
# Endstand einer Antwort, die ein Neustart abgebrochen hat – zählt nicht als Antwort
INTERRUPTED_NOTE = "_(unterbrochen – Captain wurde neu gestartet, die Antwort folgt)_"
STREAM_CURSOR = "▌"  # Zwischenstände enden damit (captain.bot.CURSOR)


def start_for_new(channel: dict, posts: list[dict], bot_id: str, oldest: int) -> int:
    """Startpunkt (``create_at > …``) für einen Kanal ohne Cursor.

    ``posts``: die Posts des Kanals seit ``oldest`` (= jetzt − Höchstalter).
    DMs/Gruppen-DMs (``D``/``G``): Der Bot ist Mitglied seit Erstellung → ab
    deren ``create_at`` (sonst ginge die erste DM an einen gerade offline Bot
    verloren). Kanäle: ab dem jüngsten System-Post „beigetreten“/„hinzugefügt“
    des Bots in ``posts``; fehlt er, liegt der Beitritt vor ``oldest`` → ab
    ``oldest``. Nie vor ``oldest``; nachgeholt werden ohnehin nur Posts an
    Captain, also keine Flut.
    """
    if channel.get("type") in ("D", "G"):
        created = int(channel.get("create_at") or 0)
        return max(created - 1, oldest)
    joined = 0
    for p in posts:
        kind = p.get("type")
        props = p.get("props") or {}
        if kind == "system_join_channel" and p.get("user_id") == bot_id:
            joined = max(joined, int(p.get("create_at") or 0))
        elif kind == "system_add_to_channel" and isinstance(props, dict) \
                and props.get("addedUserId") == bot_id:
            joined = max(joined, int(p.get("create_at") or 0))
    return max(joined, oldest)


def fetch_after(cursor: int, now: int, max_age_ms: int) -> tuple[int, bool]:
    """(Untergrenze für ``create_at > …``, ``True`` = Cursor älter als erlaubt)."""
    oldest = now - max_age_ms
    if cursor < oldest:
        return oldest, True
    return cursor, False


def order_missed(posts: list[dict]) -> list[dict]:
    """Posts aller Kanäle ohne Doppelte, chronologisch (``create_at``, dann ID)."""
    unique = {p.get("id"): p for p in posts if p.get("id")}
    return sorted(unique.values(), key=lambda p: (p.get("create_at", 0), p.get("id", "")))


def is_unfinished(message: str) -> bool:
    """Zwischenstand oder durch Neustart abgebrochene Antwort?"""
    text = (message or "").rstrip()
    return text.endswith(STREAM_CURSOR) or INTERRUPTED_NOTE in text


ANSWERS_PROP = "captain_answers"  # Props einer fertigen Antwort: beantwortete Post-IDs


def answers_props(post_ids) -> dict:
    """Props für den (letzten) Post einer fertigen Antwort."""
    return {ANSWERS_PROP: [pid for pid in post_ids if pid]}


def answered_in(posts: list[dict], bot_id: str) -> set[str]:
    """Post-IDs, die Captain laut ``props.captain_answers`` fertig beantwortet hat.

    Die Eigenschaft setzt Captain erst, wenn eine Antwort vollständig
    geschrieben ist (bei mehrteiligen Antworten am letzten Teil); Zwischenstände
    und abgebrochene Antworten tragen sie nie. Unabhängig von Zeitstempeln –
    ein wartender Post gilt also nicht als beantwortet, nur weil danach eine
    Antwort auf einen früheren Post erschien.
    """
    ids: set[str] = set()
    for p in posts:
        if p.get("user_id") != bot_id or p.get("delete_at"):
            continue
        props = p.get("props") or {}
        value = props.get(ANSWERS_PROP) if isinstance(props, dict) else None
        if isinstance(value, list):
            ids.update(str(v) for v in value if v)
    return ids


def stale_partials(posts: list[dict], bot_id: str, before: int) -> list[dict]:
    """Captain-Zwischenstände (``▌``) von vor ``before`` – Reste eines Abbruchs."""
    return [
        p for p in posts
        if p.get("user_id") == bot_id and not p.get("type") and not p.get("delete_at")
        and (p.get("message") or "").rstrip().endswith(STREAM_CURSOR)
        and int(p.get("create_at") or 0) < before
    ]
