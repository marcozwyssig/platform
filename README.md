# platform

Domain-agnostic shared core for the `*ctl` product family (netctl, infractl). See the design spec in
netctl: `docs/superpowers/specs/2026-07-07-platform-core-extraction-infractl-bootstrap-design.md`.

**Adopting the delivery kernel for a new product?** `pip install simplon`, then `simplon init <name>`.
The Python kernel now lives at [github.com/marcozwyssig/simplon](https://github.com/marcozwyssig/simplon)
and is consumed from PyPI; nothing is vendored for it. See
[`docs/adopting-the-platform.md`](docs/adopting-the-platform.md).

What remains here is the **Java block**. The products vendor this repo as a git submodule at
`lib/platform` for the Gradle composite (`includeBuild("lib/platform")`, coordinates
`info.zwyssig.platform:consensus`).

## Layout

arc42 building blocks under `src/` (platform#2, mirrors netctl#548): each block owns its code and
tests, with the language level inside the block and the test LEVEL on top.

```
src/consensus/                     Java lib block: Raft wiring, mTLS material, appliedIndex JPA
  src/java/                        Gradle module :consensus (group info.zwyssig.platform)
  test/unit/java/                  JUnit tests (+ resources/)
```

## Test

    gradle :consensus:test
