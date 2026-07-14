# `autotest-world` — detached backup branch (NOT part of the code history)

This branch holds **only** the frozen master world used by Orebit's headless end-to-end
autotest (`scripts/run-autotest.ps1 -MasterWorld ...`). It is an **orphan branch**: it shares
no history with `core` / `main` / `mc-1.21` and is never merged into them, so the ~43 MB of
Minecraft region data never enters the code branches' history or their clones.

## Why it exists

The autotest needs a **frozen, byte-stable** world. Minecraft worldgen is *not* deterministic
across regenerations — vegetation especially (trees generate in parallel chunk-gen order; the
startprobe proved one seed yields 3 distinct tree layouts in 5 runs). So the test must run
against a copied **master** world, never a seed-regenerated one. This branch is that master,
kept on GitHub so it survives a machine swap. It is a real-world proof case for the pathfinder.

## Contents

    scripts/autotest-world-master/world/   <- the pre-generated MC world (region/, level.dat, ...)

## Use it on a new machine

    git fetch origin autotest-world
    git worktree add ../orebit-autotest-world autotest-world

Then point the autotest at the `world` directory (from the mc-1.21 worktree, JDK 21):

    powershell scripts/run-autotest.ps1 -MasterWorld ../orebit-autotest-world/scripts/autotest-world-master/world

`-MasterWorld` copies this world into `run/autotest/world` each run and only ever mutates the
copy, so the master stays byte-identical. **Caveat:** the master must already contain every
chunk the bot visits along the start→goal corridor; a missing chunk is generated on the fly
from the seed → back to non-deterministic vegetation.

## Removing it later

Because it is an isolated orphan branch, deleting it needs **no history rewrite** (no
`git filter-repo`, no force-push of code branches):

    git push origin --delete autotest-world
    git branch -D autotest-world        # local

GitHub then garbage-collects the now-unreferenced blobs.
