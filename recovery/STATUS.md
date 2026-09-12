# FullHDFilmizlesene recovery

Work branch: `fix/fullhdfilmizlesene-recovery-20260912`.

Recovered v63 remains preserved as evidence. A new recovery package was rebuilt as v73 from Feroxx/Kekik-cloudstream base `7e85cf2` plus audited fixes for the 2026 FullHDFilmizlesene flow.

Implemented in v73:
- search uses `/autocomplete/q.php?q=...` JSON endpoint instead of the stale `/arama/...` HTML path;
- main-site requests use CloudStream `CloudflareKiller`;
- `scx` parsing supports balanced/multiline objects and individual list/map entries;
- ad placeholder hosts are skipped;
- RapidVid supports both legacy `window._p8` and `jwSetup.sources` forms;
- package build/DEX/package tasks PASS.

Android runtime test is still pending. The v73 package is exposed only through the isolated `repo-fhd-test.json` manifest; protected/recovered manifests were not changed.
