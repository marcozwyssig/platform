# platform

Domain-agnostic shared core for the `*ctl` product family (netctl, infractl). See the design spec in
netctl: `docs/superpowers/specs/2026-07-07-platform-core-extraction-infractl-bootstrap-design.md`.

Dev-time consumption: check this repo out as a **sibling** of the consuming product
(`<parent>/platform` next to `<parent>/netctl`). The product's shim and pytest bootstrap prepend
`platform/src` to `sys.path`. Python import package: `platformcore` (never `platform`).

## Test

    python3 -m pytest tests -v
