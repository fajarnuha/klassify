"""Smoke-test native directory creation: python3 scripts/test-noul-config.py <binary>."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


binary = str(Path(sys.argv[1]).resolve())
with tempfile.TemporaryDirectory() as directory:
    config = Path(directory) / "nested" / "config with spaces"
    env = dict(os.environ, KLASSIFY_WORKDIR=str(config))
    for question in ("Is this a fruit?", "Is this a vegetable?"):
        subprocess.run([binary, "noul", "set", "-q", question], env=env, check=True, capture_output=True)
        saved = json.loads((config / "noul.json").read_text())
        assert saved["questions"]["noul_question"]["instructions"] == question
    if os.name != "nt":
        assert config.stat().st_mode & 0o777 == 0o700
    blocked = Path(directory) / "file"
    blocked.write_text("keep")
    result = subprocess.run([binary, "noul", "set", "-q", "Is this a fruit?"],
                            env=dict(env, KLASSIFY_WORKDIR=str(blocked / "child")), capture_output=True)
    assert result.returncode != 0
    assert blocked.read_text() == "keep"
