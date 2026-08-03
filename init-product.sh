#!/usr/bin/env bash
#
# init-product.sh <name> - one-command bootstrap of a NEW product onto the delivery platform (netctl#740).
#
# Grab this ONE file standalone (copy it, or curl it from the platform repo) into an EMPTY target repo, then:
#
#     ./init-product.sh myctl
#
# It runs the whole adoption end to end:
#   1. ensures the target is a git repo (git init if it is a fresh directory);
#   2. vendors the delivery platform as a git submodule at lib/platform (the conventional path);
#   3. scaffolds the product skeleton via `python -m delivery.bootstrap <name>` (shim + <name>.yaml +
#      orchestrator package, all correct out of the box: kernel deps via -r, a working `all` aggregate,
#      marker-walk root detection);
#   4. verifies the assembled CLI by actually running `./<name>.sh help`.
#
# After it, the only work left is filling in <name>.yaml.
#
# Chicken-and-egg: the platform submodule does NOT exist yet in a brand-new repo, so this script is the one
# piece the user grabs standalone - it is NOT invoked THROUGH the submodule. Everything else lives in the
# kernel it vendors.
#
# Overrides (env vars):
#   PLATFORM_URL   the platform repo to vendor        (default: the canonical GitHub repo)
#   PLATFORM_REF   a branch / tag / sha to pin        (default: the submodule's default branch)
#   PLATFORM_PATH  where to vendor it                 (default: lib/platform)
#
set -euo pipefail

PLATFORM_URL="${PLATFORM_URL:-https://github.com/marcozwyssig/platform.git}"
PLATFORM_PATH="${PLATFORM_PATH:-lib/platform}"
PLATFORM_REF="${PLATFORM_REF:-}"

die() { printf 'init-product: %s\n' "$*" >&2; exit 1; }
note() { printf 'init-product: %s\n' "$*" >&2; }

[ "$#" -eq 1 ] || die "usage: init-product.sh <name>   (a lowercase slug, e.g. 'myctl')"
NAME="$1"

# Validate the slug the SAME way the scaffolder does, so a bad name fails HERE, not halfway through.
printf '%s' "$NAME" | grep -Eq '^[a-z][a-z0-9-]*$' \
    || die "invalid product name '$NAME'; use a lowercase slug matching [a-z][a-z0-9-]* (e.g. 'myctl')"

command -v git >/dev/null 2>&1 || die "git is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required (the orchestrator is host-Python)"

# 1. the target must be a git repo for `git submodule add` to work; init one if this is a fresh directory.
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    note "no git repo here; running git init"
    git init -q
fi
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

KERNEL_SRC="$PLATFORM_PATH/src/delivery/src/python"

# 2. vendor the platform as a submodule (idempotent: skip the add if the kernel is already present, so a
#    re-run - or running the copy that ships inside an already-vendored lib/platform - does not error out).
if [ -f "$KERNEL_SRC/delivery/bootstrap.py" ]; then
    note "$PLATFORM_PATH already vendored; skipping submodule add"
else
    note "vendoring the delivery platform at $PLATFORM_PATH (from $PLATFORM_URL)"
    git submodule add "$PLATFORM_URL" "$PLATFORM_PATH"
    if [ -n "$PLATFORM_REF" ]; then
        note "pinning $PLATFORM_PATH to $PLATFORM_REF"
        git -C "$PLATFORM_PATH" fetch -q origin "$PLATFORM_REF"
        git -C "$PLATFORM_PATH" checkout -q "$PLATFORM_REF"
    fi
    git submodule update --init --recursive "$PLATFORM_PATH"
fi
[ -f "$KERNEL_SRC/delivery/bootstrap.py" ] \
    || die "delivery kernel not found at $KERNEL_SRC after vendoring; check PLATFORM_URL / PLATFORM_REF"

# 3. scaffold the product skeleton in place. bootstrap is pure stdlib (argparse/re/pathlib), so system
#    python3 runs it with only the kernel source on PYTHONPATH - no venv, no product deps yet.
note "scaffolding '$NAME'"
PYTHONPATH="$KERNEL_SRC" python3 -m delivery.bootstrap "$NAME" --dir "$ROOT"

# 4. verify the assembled CLI actually boots: this bootstraps the host venv, installs the -r'd requirements,
#    assembles the manifest and resolves EVERY command impl. stdout is muted (the help text), stderr stays
#    visible so a first-run venv/pip issue is legible.
note "verifying ./$NAME.sh help"
chmod +x "./$NAME.sh"
if ! ./"$NAME.sh" help >/dev/null; then
    die "./$NAME.sh help failed (see the output above; a first run needs network for the host-venv pip install)"
fi

printf 'init-product: OK - %s is scaffolded and ./%s.sh help runs.\n' "$NAME" "$NAME"
printf 'init-product: next, fill in %s.yaml (add your groups + commands), then commit.\n' "$NAME"
