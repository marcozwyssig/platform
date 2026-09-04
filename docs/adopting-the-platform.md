# Onboarding: how another project adopts the platform

The platform is split in two, and a new product almost always wants the first half.

## The delivery kernel: `simplon`

The product-agnostic CI/CD core -- manifest-driven CLI assembly, env-first dispatch, the step runner
and split-pane TUI, the host-venv launcher -- left this repo and lives at
**[github.com/marcozwyssig/simplon](https://github.com/marcozwyssig/simplon)**. It is a PyPI package
now, not a submodule, so adopting it is an ordinary dependency:

```bash
# from your new product repo root
pip install simplon          # or: pipx run simplon ...
simplon init myctl --dir .
```

That writes the launcher, the manifest and the `orchestrator` package, after which the generated
`myctl.sh` provisions its own venv and installs the pinned kernel on first run. Nothing is vendored,
no `PYTHONPATH` points anywhere, and there is no `lib/platform` on the Python side at all.

The kernel's own README and its `simplon.yaml` are the reference for the manifest, the injection
seams (`ProductContext`, `EnvironmentProvider`, `impl:` binding, the aggregate step factory) and the
task catalogue. They are documented where the code is, so they cannot drift from it the way the long
guide that used to stand here did.

> The old version of this page documented the kernel as `delivery`, vendored at
> `lib/platform/src/delivery/`, adopted with `./init-product.sh`. All of that is gone; the last
> revision that matched the code is in this repo's history, at the commit before the kernel moved out.

## The Java block: `consensus`

What remains here is `src/consensus/`: Raft wiring, mTLS material and appliedIndex JPA persistence
(group `info.zwyssig.platform`, artifact `consensus`). It is still consumed by vendoring this repo as
a git submodule at `lib/platform` and adding a Gradle composite build:

```kotlin
// settings.gradle.kts in the consuming product
includeBuild("lib/platform")
```

A product that only wants the delivery kernel needs none of this.
