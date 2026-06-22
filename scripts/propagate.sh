#!/usr/bin/env sh
# propagate.sh — fan a committed `core` change out to every era branch (Model C).
#
# Model C: common/overlay/version-file changes are authored on `core`, then merged into
# each era branch. Because `core` carries no toolchain values (no era.properties), these
# merges are conflict-free. Run this AFTER committing on `core`.
#
# An "era branch" is identified structurally: any local branch whose tree tracks
# era.properties (which `core`, by definition, does not). So new era branches are picked
# up automatically — no hardcoded list to maintain.
#
# Usage:  sh scripts/propagate.sh        (run from the repo root, on any clean branch)
set -eu

if [ -n "$(git status --porcelain)" ]; then
    echo "propagate: working tree is not clean — commit or stash first." >&2
    exit 1
fi

start="$(git rev-parse --abbrev-ref HEAD)"
restore() { git checkout -q "$start" 2>/dev/null || true; }
trap restore EXIT INT TERM

for branch in $(git for-each-ref --format='%(refname:short)' refs/heads/); do
    [ "$branch" = "core" ] && continue
    # Era branches track era.properties; core does not. Skip non-era branches.
    git cat-file -e "$branch:era.properties" 2>/dev/null || continue
    echo "==> git merge core -> $branch"
    git checkout -q "$branch"
    git merge --no-edit core
done

echo "propagate: done."
