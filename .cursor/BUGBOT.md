# Bugbot rules

## Leave alone

Do not flag the absence (or removal) of `twisted-tounge/` from `.gitignore` or from
`.idea/scopes/OpenLoop_Tracked.xml`.

That DeepAR vendor tree was deleted on 2026-08-31 and is not coming back. The owner confirmed
there is no leftover folder on disk and no use for the path. The GUIDE we wrote from it lives in
`docs/guides/porting-third-party-ar-effects.md`. An ignore rule for a directory that no longer
exists is a tombstone, not a safety net.
