#!/usr/bin/env bash
set -euo pipefail

cli=$1
pack=$2
android_pack=$3
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
export KLASSIFY_RECIPE_DIR="$fixture/installed"
unset KLASSIFY_WORKDIR

"$cli" recipe doctor
mkdir -p "$fixture/empty-path" "$fixture/java17/bin"
cat > "$fixture/java17/bin/java" <<'JAVA'
#!/bin/sh
echo '    java.specification.version = 17' >&2
JAVA
chmod +x "$fixture/java17/bin/java"
if PATH="$fixture/empty-path" JAVA_HOME="$fixture/java17" "$cli" recipe doctor > "$fixture/error" 2>&1; then
    echo 'Expected Java 17 to be rejected' >&2
    exit 1
fi
grep -F 'Found Java 17. Kotlin recipes require Java 21 or newer.' "$fixture/error"
if PATH="$fixture/empty-path" JAVA_HOME="$fixture/missing-java" "$cli" recipe doctor > "$fixture/error" 2>&1; then
    echo 'Expected a missing Java installation to be reported' >&2
    exit 1
fi
grep -F 'No working Java installation was found.' "$fixture/error"
(
    cd "$fixture"
    ln -s "$fixture/java17/bin/java" java
    if PATH=":$fixture/empty-path" JAVA_HOME="$fixture/missing-java" "$cli" recipe doctor > "$fixture/error" 2>&1; then
        echo 'Expected Java in the working directory to be ignored' >&2
        exit 1
    fi
    grep -F 'No working Java installation was found.' "$fixture/error"
)
if PATH="$fixture/empty-path" JAVA_HOME="$fixture/missing-java" "$cli" recipe install "$pack" > "$fixture/error" 2>&1; then
    echo 'Expected installation to require Java for Kotlin packs' >&2
    exit 1
fi
grep -F 'Kotlin recipes require Java 21 or newer.' "$fixture/error"
PATH="$fixture/empty-path" JAVA_HOME="$fixture/missing-java" "$cli" --help > /dev/null
(
    cd "$fixture"
    ln -s "$cli" via-link
    ./via-link recipe doctor
)

[[ $("$cli" recipe list) == "No recipe packs installed." ]]
if "$cli" install missing-pack > "$fixture/error" 2>&1; then
    echo 'Expected an unknown official pack ID to be rejected' >&2
    exit 1
fi
grep -F "Unknown official pack 'missing-pack'. Choose klassify-starter or android-dev" "$fixture/error"
python3 - "$pack" "$android_pack" "$fixture/old-starter.zip" <<'PYTHON'
import json
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as starter, ZipFile(sys.argv[2]) as android, ZipFile(sys.argv[3], "w") as old:
    manifest = json.loads(starter.read("pack.json"))
    sort_files = dict(manifest["recipes"][0], runnerApi=1)
    review_triage = json.loads(android.read("pack.json"))["recipes"][0]
    manifest.update(version="0.1.0", recipes=[sort_files, review_triage])
    old.writestr("pack.json", json.dumps(manifest))
    old.writestr(sort_files["entrypoint"], 'import com.fajarnuha.klassify.recipe.recipe\nrecipe(args) {}')
    for name in (review_triage["entrypoint"], "recipes/app-review-triage.json"):
        old.writestr(name, android.read(name))
PYTHON
"$cli" recipe install "$fixture/old-starter.zip"
if "$cli" recipe install "$android_pack" > "$fixture/error" 2>&1; then
    echo 'Expected android-dev to conflict with starter 0.1.0' >&2
    exit 1
fi
grep -F "Recipe 'app-review-triage' already belongs to pack klassify-starter" "$fixture/error"
"$cli" recipe update klassify-starter "$pack"
"$cli" install "$pack"
[[ $("$cli" recipe list) == "klassify-starter 0.3.0 (sort-files, email-triage)" ]]
"$cli" recipe install "$android_pack"

"$cli" run email-triage --prepare-only > "$fixture/email-default.json"
"$cli" run email-triage --from store@example.com --subject "Invoice" --body "Payment received" \
    --criteria '{"billing":"Invoices, payments, and receipts","support":null}' --prepare-only > "$fixture/email-custom.json"
"$cli" run email-triage --subject "New invoice" --criteria billing,support --prepare-only > "$fixture/email-labels.json"
if "$cli" run email-triage --criteria billing --prepare-only > "$fixture/error" 2>&1; then
    echo 'Expected email triage to reject a single label' >&2
    exit 1
fi
grep -F -- '--criteria needs 2 to 255 nonblank labels' "$fixture/error"

mkdir -p "$fixture/input's files/nested" "$fixture/empty"
touch "$fixture/input's files/résumé.pdf" "$fixture/input's files/photo.png" "$fixture/input's files/nested/skip.txt"
ln -s photo.png "$fixture/input's files/link.png"
"$cli" run sort-files --dir "$fixture/input's files" --folders=documents,images --prepare-only > "$fixture/sorted.json"
if "$cli" run sort-files --dir "$fixture/input's files" --folders documents,images --apply \
    < /dev/null > "$fixture/error" 2>&1; then
    echo 'Expected --apply to require an interactive terminal' >&2
    exit 1
fi
grep -F -- '--apply requires an interactive terminal' "$fixture/error"
"$cli" run sort-files --dir "$fixture/empty" --folders documents,images --api-key '' > "$fixture/empty.jsonl"
test ! -s "$fixture/empty.jsonl"

printf 'id,text,rating,appVersion,platform\r\nkept,"Crashes, even after restart.\r\nTapped ""Open"".",1,1.8.0,Android\r\n\r\nold,Great,5,1.7.0,iOS\r\nblank, ,2,1.8.0,Android\r\n' > "$fixture/reviews.csv"
"$cli" run app-review-triage --reviews "$fixture/reviews.csv" --app-version 1.8.0 --prepare-only > "$fixture/reviews.json"

python3 - "$fixture" "$pack" <<'PY'
import json
import sys
from pathlib import Path
from zipfile import ZipFile

root = Path(sys.argv[1])
with ZipFile(sys.argv[2]) as starter:
    sample = json.loads(starter.read("recipes/email-triage.json"))
default_email = json.loads((root / "email-default.json").read_text())
assert default_email["questions"] == sample["questions"]
assert default_email["items"] == [{"id": "email", "state": sample["state"]}]
custom_email = json.loads((root / "email-custom.json").read_text())
assert custom_email["questions"]["category"]["criteria"] == {
    "billing": "Invoices, payments, and receipts", "support": None,
}
assert custom_email["items"] == [{"id": "email", "state": {
    "from": "store@example.com", "subject": "Invoice", "body": "Payment received",
}}]
label_email = json.loads((root / "email-labels.json").read_text())
assert label_email["questions"]["category"]["criteria"] == {"billing": None, "support": None}
assert label_email["items"] == [{"id": "email", "state": {"subject": "New invoice"}}]

folders = json.loads((root / "sorted.json").read_text())
assert list(folders["questions"]["folder"]["criteria"]) == ["documents", "images"]
assert folders["items"] == [
    {"id": "photo.png", "state": {"name": "photo.png", "extension": "png"}},
    {"id": "résumé.pdf", "state": {"name": "résumé.pdf", "extension": "pdf"}},
]
assert (root / "input's files" / "photo.png").is_file()
reviews = json.loads((root / "reviews.json").read_text())
assert set(reviews["questions"]) == {"category", "priority"}
assert reviews["items"] == [
    {"id": "kept", "state": {
        "text": 'Crashes, even after restart.\r\nTapped "Open".',
        "rating": 1,
        "appVersion": "1.8.0",
        "platform": "Android",
    }},
]
PY

printf 'id,text,rating,appVersion\n1,Wrong rating,9,1.8.0\n' > "$fixture/invalid.csv"
if "$cli" run app-review-triage --reviews "$fixture/invalid.csv" --prepare-only > "$fixture/error" 2>&1; then
    echo 'Expected the invalid CSV to fail' >&2
    exit 1
fi
grep -F 'CSV line 2: rating must be an integer from 1 to 5' "$fixture/error"

python3 - "$fixture" <<'PYTHON'
import json
import sys
from pathlib import Path
from zipfile import ZipFile

root = Path(sys.argv[1])

def write_pack(filename, version, name, script='error("Pack management must not execute recipe scripts")', update_url=None):
    manifest = {
        "schemaVersion": 1,
        "id": "second-pack",
        "version": version,
        "recipes": [{
            "name": name,
            "entrypoint": "second.recipe.kts",
            "runtime": "kotlin-jvm",
            "runnerApi": 1,
        }],
    }
    if update_url is not None:
        manifest["updateUrl"] = update_url
    with ZipFile(root / filename, "w") as archive:
        archive.writestr("pack.json", json.dumps(manifest))
        archive.writestr("second.recipe.kts", script)

write_pack("second.zip", "1.0.0", "second-recipe")
write_pack("second-rc2.zip", "1.1.0-rc.2", "second-recipe")
write_pack("second-rc10.zip", "1.1.0-rc.10", "second-recipe")
write_pack("second-new.zip", "1.1.0", "second-recipe-v2")
write_pack("second-changed.zip", "1.1.0", "second-recipe", 'error("Different contents")')
write_pack("second-conflict.zip", "1.2.0", "sort-files")
write_pack("second-insecure.zip", "1.2.0", "second-recipe", update_url="http://example.com/latest.zip")
PYTHON

expect_update_failure() {
    local expected=$1
    shift
    if "$cli" recipe update "$@" > "$fixture/error" 2>&1; then
        echo "Expected recipe update to fail for $*" >&2
        exit 1
    fi
    grep -F "$expected" "$fixture/error"
}

expect_update_failure "Recipe pack 'second-pack' is not installed." second-pack
expect_update_failure "Recipe pack 'second-pack' is not installed." second-pack "$fixture/second.zip"
"$cli" recipe install "$fixture/second.zip"
[[ $("$cli" recipe list) == $'android-dev 0.1.0 (app-review-triage)\nklassify-starter 0.3.0 (sort-files, email-triage)\nsecond-pack 1.0.0 (second-recipe)' ]]
expect_update_failure "Pack 'second-pack' has no update URL." second-pack
expect_update_failure "Expected pack 'second-pack', but the archive contains 'klassify-starter'" second-pack "$pack"
expect_update_failure "'updateUrl' must be an absolute HTTPS URL" second-pack "$fixture/second-insecure.zip"
"$cli" recipe update second-pack "$fixture/second-rc2.zip"
"$cli" recipe update second-pack "$fixture/second-rc10.zip"
[[ $("$cli" recipe update second-pack "$fixture/second-new.zip") == "Updated second-pack from 1.1.0-rc.10 to 1.1.0 (second-recipe-v2)" ]]
[[ $("$cli" recipe update second-pack "$fixture/second-new.zip") == "second-pack 1.1.0 is already up to date." ]]
expect_update_failure "must be newer than installed version 1.1.0" second-pack "$fixture/second-rc10.zip"
expect_update_failure "is already installed with different contents" second-pack "$fixture/second-changed.zip"
expect_update_failure "Recipe 'sort-files' already belongs to pack klassify-starter" second-pack "$fixture/second-conflict.zip"
[[ $("$cli" recipe list) == $'android-dev 0.1.0 (app-review-triage)\nklassify-starter 0.3.0 (sort-files, email-triage)\nsecond-pack 1.1.0 (second-recipe-v2)' ]]

if "$cli" recipe uninstall ../klassify-starter > "$fixture/error" 2>&1; then
    echo 'Expected invalid pack IDs to be rejected' >&2
    exit 1
fi
grep -F "Invalid pack ID '../klassify-starter'" "$fixture/error"

"$cli" recipe uninstall klassify-starter
[[ $("$cli" recipe list) == $'android-dev 0.1.0 (app-review-triage)\nsecond-pack 1.1.0 (second-recipe-v2)' ]]
if "$cli" run sort-files --folders documents,images --prepare-only > "$fixture/error" 2>&1; then
    echo 'Expected the removed recipe to be unavailable' >&2
    exit 1
fi
grep -F "Installed recipe 'sort-files' was not found" "$fixture/error"
if "$cli" recipe uninstall klassify-starter > "$fixture/error" 2>&1; then
    echo 'Expected uninstall to report a missing pack' >&2
    exit 1
fi
grep -F "Recipe pack 'klassify-starter' is not installed." "$fixture/error"

KLASSIFY_WORKDIR="$(cd "$(dirname "$0")/../recipe" && pwd)" "$cli" recipe check pet
"$cli" recipe uninstall second-pack
[[ $("$cli" recipe list) == "android-dev 0.1.0 (app-review-triage)" ]]
"$cli" recipe uninstall android-dev
[[ $("$cli" recipe list) == "No recipe packs installed." ]]

"$cli" install "$pack"
version=$("$cli" --version)
runner_lib="$(dirname "$cli")/klassify-recipe-runner/${version#klassify version }/lib"
runner() {
    java -cp "$runner_lib/*" com.fajarnuha.klassify.recipe.RunnerKt "$@"
}

mkdir -p "$fixture/move/files"
printf 'photo contents' > "$fixture/move/files/photo.png"
printf 'invoice contents' > "$fixture/move/files/invoice.pdf"
runner prepare "$KLASSIFY_RECIPE_DIR" sort-files --dir "$fixture/move/files" --folders documents,images > "$fixture/prepared.json"
python3 - "$fixture" <<'PY'
import json
import sys
from pathlib import Path

fixture = Path(sys.argv[1])
prepared = json.loads((fixture / "prepared.json").read_text())
request = {
    "sourceFingerprint": prepared["sourceFingerprint"],
    "options": ["--dir", str(fixture / "move/files"), "--folders", "documents,images"],
    "results": [
        {"id": name, "result": {"answers": {"folder": {"choice": folder}}}}
        for name, folder in [("invoice.pdf", "documents"), ("photo.png", "images")]
    ],
}
(fixture / "postprocess.json").write_text(json.dumps(request))
PY
runner postprocess "$KLASSIFY_RECIPE_DIR" sort-files < "$fixture/postprocess.json" > "$fixture/file-plan.json"
test -f "$fixture/move/files/photo.png"
test -f "$fixture/move/files/invoice.pdf"
test ! -e "$fixture/move/files/documents"
test ! -e "$fixture/move/files/images"

mkdir "$fixture/move/files/images"
printf 'existing file' > "$fixture/move/files/images/photo.png"
if runner apply "$KLASSIFY_RECIPE_DIR" < "$fixture/file-plan.json" > "$fixture/error" 2>&1; then
    echo 'Expected a destination created after preview to block the moves' >&2
    exit 1
fi
grep -F 'Destination already exists' "$fixture/error"
test -f "$fixture/move/files/invoice.pdf"
test "$(cat "$fixture/move/files/images/photo.png")" = "existing file"
rm "$fixture/move/files/images/photo.png"

printf 'revised invoice' > "$fixture/move/files/invoice.pdf"
if runner apply "$KLASSIFY_RECIPE_DIR" < "$fixture/file-plan.json" > "$fixture/error" 2>&1; then
    echo 'Expected a file changed after preview to block the moves' >&2
    exit 1
fi
grep -F 'Source changed after preview' "$fixture/error"
test -f "$fixture/move/files/photo.png"
runner postprocess "$KLASSIFY_RECIPE_DIR" sort-files < "$fixture/postprocess.json" > "$fixture/file-plan.json"
test "$(runner apply "$KLASSIFY_RECIPE_DIR" < "$fixture/file-plan.json")" = "2"
test "$(cat "$fixture/move/files/images/photo.png")" = "photo contents"
test "$(cat "$fixture/move/files/documents/invoice.pdf")" = "revised invoice"
test ! -e "$fixture/move/files/photo.png"
test ! -e "$fixture/move/files/invoice.pdf"
