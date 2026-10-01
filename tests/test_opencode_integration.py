"""Integrationstests gegen den laufenden opencode-Server (infra/opencode).

Zugang aus der env-Datei des Test-Setups (``OPENCODE_SERVER_PASSWORD``,
``OPENCODE_MODEL``); Pfad per ``CAPTAIN_OC_ENV`` überschreibbar, fehlt sie,
wird übersprungen. Server-URL per ``OPENCODE_URL`` (Default
``http://localhost:4096``), Arbeitsverzeichnis *im Container* per
``CAPTAIN_OC_DIR`` (Default ``/tmp`` – existiert immer, der Client legt keine
Verzeichnisse an).

Tool-Aufrufe (Datei schreiben, Shell/Web gesperrt) prüft
``test_restrict_integration.py`` – ohne Session-Regeln hat eine Session seit
#17 gar keine Tools.

Achtung: Mit ``ollama/gemma4:e4b`` auf CPU dauert der erste Prompt nach einem
Kaltstart ~2 min; die Timeouts sind entsprechend großzügig.
"""

import os
import threading
import time

import pytest

from captain.opencode import Interrupted, OpencodeClient

pytestmark = pytest.mark.integration

ENV_PATH = os.environ.get("CAPTAIN_OC_ENV", "C:/source/captain-shared/opencode.env")
URL = os.environ.get("OPENCODE_URL", "http://localhost:4096")
DIRECTORY = os.environ.get("CAPTAIN_OC_DIR", "/tmp")
IDLE = 600


def read_env(path):
    values = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    return values


@pytest.fixture(scope="module")
def env():
    if not os.path.exists(ENV_PATH):
        pytest.skip(f"opencode-Zugang fehlt: {ENV_PATH}")
    return read_env(ENV_PATH)


@pytest.fixture(scope="module")
def oc(env):
    with OpencodeClient(URL, env.get("OPENCODE_SERVER_PASSWORD")) as client:
        yield client


@pytest.fixture(scope="module")
def model(env):
    return env.get("OPENCODE_MODEL") or None


def test_session_lifecycle(oc):
    sid = oc.create_session(DIRECTORY, title="captain-test lifecycle")
    assert sid.startswith("ses")
    assert oc.session_exists(sid)
    assert not oc.session_exists("ses_gibtesnicht")
    oc.abort(sid)  # idle → harmlos


def test_list_models(oc, model):
    models = oc.list_models()
    assert models, "keine Modelle"
    assert all({"id", "name", "variants"} <= m.keys() for m in models)
    if model:
        assert model in {m["id"] for m in models}


def test_context_and_deltas(oc, model):
    sid = oc.create_session(DIRECTORY, title="captain-test kontext")
    deltas = []
    first = oc.prompt(
        sid,
        "Merk dir die Zahl 42. Antworte nur mit: OK",
        directory=DIRECTORY,
        model=model,
        on_delta=deltas.append,
        idle_timeout=IDLE,
    )
    assert first.strip()
    deltas.clear()
    second = oc.prompt(
        sid,
        "Welche Zahl solltest du dir merken? Antworte mit einem ausführlichen Satz.",
        directory=DIRECTORY,
        model=model,
        on_delta=deltas.append,
        idle_timeout=IDLE,
    )
    assert "42" in second
    assert len(deltas) >= 2, deltas
    assert deltas[-1] == second


def test_parallel_sessions(oc, model):
    sids = [oc.create_session(DIRECTORY, title=f"captain-test parallel {i}") for i in (1, 2)]
    words = ["Apfel", "Birne"]
    results: dict[int, str] = {}
    errors: list[BaseException] = []

    def run(i):
        try:
            results[i] = oc.prompt(
                sids[i],
                f"Antworte nur mit dem Wort: {words[i]}",
                directory=DIRECTORY,
                model=model,
                idle_timeout=IDLE,
            )
        except BaseException as e:  # noqa: BLE001
            errors.append(e)

    threads = [threading.Thread(target=run, args=(i,)) for i in (0, 1)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(IDLE + 60)
    assert not errors, errors
    assert words[0] in results[0] and words[1] not in results[0]
    assert words[1] in results[1] and words[0] not in results[1]


def test_abort(oc, model):
    sid = oc.create_session(DIRECTORY, title="captain-test abort")
    started = threading.Event()

    def on_delta(_):
        started.set()

    def stopper():
        started.wait(IDLE)
        time.sleep(0.5)
        oc.abort(sid)

    threading.Thread(target=stopper, daemon=True).start()
    with pytest.raises(Interrupted):
        oc.prompt(
            sid,
            "Schreibe einen sehr langen Aufsatz mit 30 Absätzen über die Geschichte der Seefahrt.",
            directory=DIRECTORY,
            model=model,
            on_delta=on_delta,
            idle_timeout=IDLE,
        )
