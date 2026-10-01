#!/usr/bin/env python3
"""Check the Gradle version bump task without changing the checkout's version."""
import pathlib
import shutil
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as directory:
    project = pathlib.Path(directory)
    shutil.copy(root / "gradle/versioning.gradle.kts", project / "versioning.gradle.kts")
    (project / "settings.gradle.kts").write_text('rootProject.name = "versioning-test"\n')
    (project / "build.gradle.kts").write_text('apply(from = "versioning.gradle.kts")\n')
    properties = project / "gradle.properties"
    for part, expected in [(None, "1.3.0"), ("minor", "1.3.0"), ("patch", "1.2.4"), ("major", "2.0.0"), ("invalid", None)]:
        original = "# Keep settings\nVERSION_NAME=1.2.3\nother.setting=true\n"
        properties.write_text(original)
        command = [str(root / "gradlew"), "-p", directory, "bumpVersion", "--console=plain"]
        if part is not None:
            command.append(f"-PversionBump={part}")
        result = subprocess.run(command, capture_output=True, text=True)
        assert (result.returncode == 0) == (expected is not None), result.stdout + result.stderr
        assert properties.read_text() == (original.replace("1.2.3", expected) if expected else original)
print("Versioning checks passed")
