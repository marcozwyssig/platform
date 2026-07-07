# platform

Domain-agnostic shared core for the `*ctl` product family (netctl, infractl). See the design spec in
netctl: `docs/superpowers/specs/2026-07-07-platform-core-extraction-infractl-bootstrap-design.md`.

Dev-time consumption: check this repo out as a **sibling** of the consuming product
(`<parent>/platform` next to `<parent>/netctl`). The product's shim and pytest bootstrap prepend
`platform/src/python` to `sys.path`. Python import package: `platformcore` (never `platform`).

## Layout

Mirrors netctl: `src/python/` (Python packages, e.g. `platformcore`) and `src/java/` (Java modules,
Phase 2). Tests live under `test/python/` (unit) and `test/java/` (Phase 2).

## Test

    python3 -m pytest test/python/unit -v
