"""Führt die Node-Unit-Tests des opencode-Startskripts aus (infra/opencode/start.test.mjs).

Nutzt ein lokales ``node`` (≥ 18); ohne Node wird übersprungen – im
Integrationslauf prüft ``tests/test_config_layers_integration.py`` dasselbe
Skript im Image.
"""

import pathlib
import shutil
import subprocess

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
TESTS = ROOT / "infra" / "opencode" / "start.test.mjs"


def test_start_script_unit_tests():
    node = shutil.which("node")
    if not node:
        pytest.skip("node nicht installiert")
    out = subprocess.run([node, "--test", str(TESTS)], capture_output=True, text=True,
                         encoding="utf-8", errors="replace", timeout=120)
    assert out.returncode == 0, out.stdout[-4000:] + out.stderr[-2000:]
