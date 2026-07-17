# platform

Domain-agnostic shared core for the `*ctl` product family (netctl, infractl). See the design spec in
netctl: `docs/superpowers/specs/2026-07-07-platform-core-extraction-infractl-bootstrap-design.md`.

Consumption: the products vendor this repo as a **git submodule at `lib/platform`**. The product's
shim and pytest bootstrap prepend `lib/platform/src/delivery/src/python` to `sys.path` /
`PYTHONPATH`; Java is consumed via a Gradle composite (`includeBuild("lib/platform")`, coordinates
`info.zwyssig.platform:consensus`). Python import package: `platformcore` (never `platform` - that
name shadows the Python stdlib module).

## Layout

arc42 building blocks under `src/` (platform#2, mirrors netctl#548): each block owns its code and
tests, with the language level inside the block and the test LEVEL on top.

```
src/consensus/                     Java lib block: Raft wiring, mTLS material, appliedIndex JPA
  src/java/                        Gradle module :consensus (group info.zwyssig.platform)
  test/unit/java/                  JUnit tests (+ resources/)
src/delivery/                      Python lib block: the CI/CD orchestrator engine + seams
  src/python/platformcore/         import package (orchestrator/, clitaxonomy, environments, ...)
  test/unit/python/                pytest unit tests (conftest.py adds src/python to sys.path)
```

## Test

    python3 -m pytest src/delivery/test/unit/python -v
    gradle :consensus:test
