"""Führt die Node-Unit-Tests des opencode-Startskripts aus.

``infra/opencode/start.test.mjs`` (Admin-Config, Umgebung),
``infra/opencode/webfetch.test.mjs`` (webfetch-Allowlist, Egress-Filter) und
``infra/opencode/shared.test.mjs`` (geteiltes Verzeichnis).

Nutzt ein lokales ``node`` (≥ 18); ohne Node wird übersprungen – im
Integrationslauf prüft ``tests/test_config_layers_integration.py`` dasselbe
Skript im Image.
"""

import pathlib
import shutil
import subprocess

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
TESTS = [ROOT / "infra" / "opencode" / name for name in ("start.test.mjs", "webfetch.test.mjs", "shared.test.mjs")]


@pytest.mark.parametrize("tests", TESTS, ids=lambda p: p.name)
def test_start_script_unit_tests(tests):
    node = shutil.which("node")
    if not node:
        pytest.skip("node nicht installiert")
    out = subprocess.run([node, "--test", str(tests)], capture_output=True, text=True,
                         encoding="utf-8", errors="replace", timeout=120)
    assert out.returncode == 0, out.stdout[-4000:] + out.stderr[-2000:]
