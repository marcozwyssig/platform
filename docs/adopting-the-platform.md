# Onboarding: how another project adopts the `delivery` platform

The `delivery` kernel is the product-agnostic shared core for the `*ctl` family (netctl =
network automation, infractl = IaaS/PaaS). It ships **mechanism only**: the manifest-driven CLI
assembly, the env-first dispatch, the step runner + split-pane TUI, and the host-venv
launcher. Your product ships **data + a thin adapter**: one manifest, one shim, and a small
`orchestrator` package with your command implementations. The invariant the kernel is built around
is *"gleiche Maschine, anderer Katalog"* — same machine, different catalog.

Adopting the platform is **one command** - `./init-product.sh <name>` (see the Quickstart just
below). Under it sits `python -m delivery.bootstrap`, the scaffolder that writes the working skeleton;
this guide documents both, plus the seams a product grows into, using **netctl** (this repo, at
`deploy/provision/orchestrator/`) as the worked example. Every path and API below is real kernel code
under `lib/platform/src/delivery/src/python/delivery/`.

---

## Quickstart: one command

For a brand-new product the entire adoption is a single script, `init-product.sh` at the platform
repo root (netctl#740). Grab it standalone - the platform submodule does not exist yet, so you cannot
run anything *through* it - then run it with your product name:

```bash
# from your new product repo root
curl -fsSL https://raw.githubusercontent.com/marcozwyssig/platform/main/init-product.sh -o init-product.sh
chmod +x init-product.sh
./init-product.sh myctl
```

It runs the whole flow end to end and leaves a working CLI:

1. `git init` if the directory is not a repo yet;
2. `git submodule add … lib/platform` - vendor the platform at the conventional path;
3. `python -m delivery.bootstrap myctl` - scaffold the shim, `myctl.yaml` and the `orchestrator` package;
4. verify by actually running `./myctl.sh help`.

When it prints `OK`, the only work left is filling in `myctl.yaml`. Overrides (env vars):
`PLATFORM_URL`, `PLATFORM_REF`, `PLATFORM_PATH`. The rest of this guide explains what the script sets
up and the seams you then extend; §2 is that same scaffold step run by hand.

---

## 1. The boundary: kernel vs product

| Concern | Kernel (`delivery`, vendored at `lib/platform`) | Your product |
|---|---|---|
| CLI assembly | `delivery.cli.assemble` — sub-app per group, hidden flat aliases, CI/CD help panels | Create the root `typer.Typer` app + call `assemble` |
| Env-first dispatch | `delivery.cli.main` — consumes `dev\|test\|…` token, applies aliases, runs the env-gate | Inject `context`, `environments`, `aliases` |
| Manifest schema | `delivery.orchestrator.manifest` — Pydantic parse/validate, `load`, `resolve_impl` | Write `<product>.yaml` |
| Command taxonomy / env-gate | `delivery.clitaxonomy.CommandTaxonomy` (pure) | (none — derived from your manifest) |
| Env registry types | `delivery.environments` — `Environment`, `Registry`, `parse_data` | Supply valid backend names + the gate |
| Product identity seam | `delivery.context.ProductContext` / `set_current` / `current` | Register one context in `paths.py` |
| Aggregate runner | `delivery.orchestrator.product.run_command` + `steps` (`Step`/`Pipeline`/`dispatch`) + `tui` | Supply a command→`Step` factory via `assemble(step_context=…)` |
| Host-venv bootstrap | `src/delivery/src/sh/launch.sh` — ensurepip probe, venv, `pip install -r`, `exec python -m` | A ~5-line shim that sets 4 env vars |
| Kernel dependencies | `src/delivery/requirements.txt` (owns typer/click/pyyaml/pydantic/rich/textual, #730) | `-r` it + add product-only deps |

The coupling flows **product → kernel** only. The kernel imports nothing named from your product;
your product's build data (image names, cache volumes, …) lives in extra top-level manifest keys the
CLI engine **ignores** (see netctl.yaml `images:`/`volumes:`).

---

## 2. Quickstart: run the bootstrap scaffolder

The scaffolder is the ONE kernel entry that runs **without** a product context — a brand-new product
has no shim, manifest or package yet, so it cannot reach the kernel through its own CLI. Put the
kernel's python source on `sys.path` (e.g. a checked-out submodule) and run:

```bash
# kernel source on PYTHONPATH, then scaffold a product named "fooctl"
PYTHONPATH=lib/platform/src/delivery/src/python \
  python -m delivery.bootstrap fooctl [--dir DIR] [--force]
```

`--dir` defaults to `./<product>`; `--force` overwrites (it refuses to clobber an existing tree
otherwise). A bad slug or a clobber conflict exits `2` with a clean message, no traceback. A product
name must match `^[a-z][a-z0-9-]*$` (a letter, then letters/digits/hyphens).

It writes exactly these 8 files (`delivery.bootstrap._templates`):

```
fooctl.sh                                         # the shim onto lib/platform's launch.sh (0o755)
fooctl.yaml                                        # the starter manifest (validates through manifest.load)
orchestrator/requirements.txt                      # host-venv deps: -r the kernel + product-only pins (#730)
orchestrator/src/python/orchestrator/
    __init__.py                                    # the product package
    __main__.py                                    # `python -m orchestrator` entry
    cli.py                                          # composition root: impls + assemble (step_context) + main
    paths.py                                        # ProductContext wiring; root via a manifest-marker walk
    environments.py                                # EnvironmentProvider (backends + env-gate)
```

The package is **always** named `orchestrator` (mirroring netctl), so a hyphenated product slug never
has to be a Python identifier. Then, per the printed next-steps (exactly what `init-product.sh`
automates):

```bash
cd fooctl
git init
git submodule add https://github.com/marcozwyssig/platform.git lib/platform
./fooctl.sh help          # the assembled, manifest-driven CLI runs
```

`./fooctl.sh help` boots because the generated `paths.py` registers a `ProductContext` and the
generated manifest validates through `delivery.orchestrator.manifest.load` — the CLI is assembled
from data, not hand-written.

**Adopting into an EXISTING repo: where `orchestrator/` lands.** The scaffolder writes the block at the
repo **top level** (`orchestrator/`), which is right for a fresh product and wrong for a repo that has a
rule about what may sit there — netctl moved it to `deploy/provision/orchestrator/`, and so did
biz-cockpit. Scaffolding into an existing tree is safe regardless: `delivery.bootstrap.write()` refuses to
overwrite anything without `--force`, so a hand-edited manifest or shim is never clobbered; only the
placement needs follow-up. Relocating costs **three lines**, all of them outside the package — the Python
side self-locates by walking up to the `<product>.yaml` marker (§4a) and needs no edit: the
`-r ../lib/platform/…` count in `orchestrator/requirements.txt` (one `../` per extra level, §5), and
`PYTHONPATH=…/orchestrator/src/python` plus `LAUNCH_ORCH_DIR=…/orchestrator` in `<product>.sh`. Move the
directory, retune those three, and `./<product>.sh help` runs from the new location.

---

## 3. The manifest: nested groups → commands → spec

The manifest is **one hierarchy** (#729): `groups` maps each group to a *group → command → spec*
tree, so a command's membership and its spec live together. (The former flat `commands:` map and its
dotted `test.all`/`deploy.all` keys are gone — nesting resolves a name owned by several groups.)

Minimal but complete example (this is the scaffolded `fooctl.yaml`, lightly annotated):

```yaml
product: fooctl                                    # label: shim/manifest name + diagnostics

groups:                                            # the ONE command tree, along the CI/CD loop
  build:                                           # single-member group whose member shares its name...
    build: { impl: "orchestrator.cli:build", help: "Build the product artefacts." }
  deploy:                                          # ...a multi-member, env-first CD group
    up:   { impl: "orchestrator.cli:up",   help: "Deploy to the target environment." }
    down: { impl: "orchestrator.cli:down", help: "Tear the deployment down." }
    all:                                           # an impl-less AGGREGATE (#895): no impl, only deps
      help: "Run build then deploy up end to end."
      depends_on: [build, up]                      # each entry names a command declared above
      stop_on_failure: false                       # default: run every planned step, take the worst rc

env_groups: [deploy]                               # the env-first CD groups (rest are agnostic)

default: dev                                       # the env matrix (#15, folded into one manifest #651)
environments:
  dev: { backend: local, description: "Local development environment." }
```

**Command spec fields** (`CommandSpec`): `help` (one-line summary, required), plus EITHER `impl`
(`"module:function"`, a leaf) OR `depends_on` (a list of command names, an impl-less aggregate, #895) -
never both. Optional: `passthrough_args: true` on a leaf (forward unrecognised trailing args to an
underlying tool — netctl uses it on `accept` → pytest) and `stop_on_failure: true` on an aggregate (a
failed plan step skips the rest of THAT aggregate's subtree).

`stop_on_failure` is scoped to the subtree that declares it, not to the run (netctl#1317). On a failure
the scope is the **outermost** ancestor of the failed leaf whose flag is true, and the rest of that node's
subtree is skipped. The nearest flagged ancestor stops first, that abort is a failure its own parent sees,
and each further ancestor decides by its own flag whether to carry on with its siblings. An explicit
`false` therefore reads exactly like an unset flag: it declines to stop for a failure, it does not shield
its subtree from a `true` above it. So a `test all` aggregate can stay `false` (every gate runs, the report
is written) while the `up` it plans stays `true` (a dead preflight guard aborts the bring-up instead of
deploying for forty minutes). A pipeline built by hand, with no plan tree behind it, keeps the single-flag
behaviour: one failure skips everything after it.

The flag is rejected on a leaf, like `keep_awake` and `hidden`: a leaf's subtree is the leaf, so there is
nothing left below it to skip. Declare it on the aggregate whose remaining steps should go.

**`load()` validates loudly** (each violation is a `ValueError`):

1. `groups` is non-empty;
2. every `env_groups` entry names a declared group;
3. every spec has a non-empty `help` and either a well-formed `module:function` `impl` or a non-empty
   `depends_on` - never both;
4. every `depends_on` entry names a known, unambiguous command;
5. the dependency graph is acyclic.

Unknown **top-level** keys are ignored, which is where your product build data lives (netctl.yaml
carries `images:`, `volumes:`, `doctoolchain_version:`, `topology:` — read raw via
`ProductContext.manifest_data()`, never through the CLI engine). ONE exception: a leftover
`composites:` key is rejected loudly - the composites concept was removed (netctl#898); declare an
impl-less aggregate command with `depends_on` instead.

**Taxonomy behaviours the shape drives** (`delivery.clitaxonomy` + `assemble`):

- **Flat-collapsed group** — a single-member group whose member equals the group name (`build`)
  collapses to ONE visible flat top-level command: `fooctl build`.
- **Group-default group** — a *multi*-member group that also contains a member named after the group
  (netctl's `build` = build/diff/docs) becomes a sub-app whose bare token runs the namesake:
  `netctl build` runs the pipeline, `netctl build diff` dispatches the sibling.
- **Ambiguous name** — a name owned by >1 group (netctl's `all` in both `test` and `deploy`) gets NO
  flat alias; it is reachable only via its group token (`netctl test all` vs `netctl <env> deploy all`).
- **Env-gate** — env-first groups land in the CD help panel and gate on the active backend; agnostic
  groups reject an explicit env prefix.

---

## 4. The injection seams a product implements

### 4a. `ProductContext` — product identity (`paths.py`)

Your paths adapter DERIVES the repo root + manifest path and registers ONE context at import; kernel
code reads it back via `delivery.context.current()`, so it never hardcodes your product name. The
scaffolded `paths.py`:

```python
from delivery import context

# The manifest doubles as the repo-root MARKER: walk up from this file to the dir holding fooctl.yaml.
_MANIFEST_NAME = "fooctl.yaml"

def _find_root(start: Path, marker: str) -> Path:
    for candidate in (start, *start.parents):
        if (candidate / marker).is_file():
            return candidate
    raise RuntimeError(f"fooctl: cannot locate '{marker}' walking up from {start}")

_DERIVED_ROOT = _find_root(Path(__file__).resolve().parent, _MANIFEST_NAME)
_DERIVED_MANIFEST = _DERIVED_ROOT / _MANIFEST_NAME

CONTEXT = context.set_current(
    context.ProductContext.resolve("fooctl", _DERIVED_ROOT, _DERIVED_MANIFEST))
ROOT = CONTEXT.root
MANIFEST = CONTEXT.manifest_path
```

`.resolve()` lets the kernel env vars `DELIVERY_PRODUCT_ROOT` / `DELIVERY_MANIFEST` override the
derived defaults (a relocated checkout, a test fixture tree); with neither set the UX is byte-
identical. Root detection is a **marker-walk**, not a fixed parent depth (netctl#737): it climbs to
the directory that holds `<product>.yaml`, so relocating the `orchestrator` package deeper in the tree
(as netctl did to `deploy/provision/orchestrator/`) needs no hand-edit. Keep the manifest at the repo
root and the walk always finds it.

### 4b. `EnvironmentProvider` — the environments seam (`environments.py`)

`delivery.cli.main` needs a small **structural** Protocol from your product (nothing named is
imported — the module just has to expose these members):

```python
class EnvironmentProvider(Protocol):
    ENV_VAR: str      # the process var the active env rides in (e.g. "FOOCTL_ENV")
    LOCAL: str        # the backend name a CD command gates on
    def names(self) -> list[str]: ...
    def default(self) -> str: ...
    def is_local(self, name: str | None = ...) -> bool: ...
    def require_backend(self, backend: str = ...) -> None: ...
```

The scaffolded `environments.py` satisfies it by reading the manifest's `environments:`/`default:`
sections straight from the context and delegating to `delivery.environments.parse_data` with YOUR
valid backends:

```python
ENV_VAR = "FOOCTL_ENV"
LOCAL = "local"
_VALID_BACKENDS = (LOCAL,)          # widen this to add a cloud backend

def _registry() -> Registry:
    data = context.current().manifest_data()
    return _parse_data(data, _VALID_BACKENDS)   # validates backends + default, loudly
```

netctl's version adds `EXOSCALE` to `_VALID_BACKENDS` and dies clean in `require_backend` when a
non-local env is targeted (the cloud path is unimplemented, #11).

### 4c. `impl:` binding — command declaration → callable

The manifest's `"module:function"` becomes the real callable at **assemble time**:
`delivery.orchestrator.manifest.resolve_impl` imports the module and `getattr`s the function.
Conventions:

- **Point at module-level callables in your `orchestrator.cli`.** netctl points every impl at its
  Typer command callbacks (not the bare delegates) because those carry the `Option`/`Argument`/
  `Context` signatures Typer introspects — a bare delegate would drop them.
- The functions must be **defined before** `assemble(...)` runs (it runs at import of `cli.py`).
- A stale ref fails loudly (`ValueError: impl '…': module '…' has no attribute '…'`), never silently.

### 4d. The composition root (`cli.py`)

`cli.py` is where your product's voice + wiring live. The scaffolded shape:

```python
import typer
from delivery import cli as delivery_cli
from . import environments, paths

app = typer.Typer(add_completion=False, no_args_is_help=True, help="fooctl orchestrator …")
_ALIASES: dict[str, str] = {}                 # old-token → canonical (empty for a fresh product)

def build() -> None: ...                      # the impl callables the manifest refs resolve to
def up() -> None: ...
def down() -> None: ...

# assemble at import: binds each manifest command's callback onto `app` (step_context binds the
# impl-less aggregates, see §4e)
delivery_cli.assemble(app, _MANIFEST, product=paths.CONTEXT.name, step_context=_STEP_CONTEXT)

def main() -> None:                           # `python -m orchestrator`
    delivery_cli.main(app=app, context=paths.CONTEXT,
                      environments=environments, aliases=_ALIASES)
```

You may register product-only internal commands on `app` with `@app.command` **before** `assemble`;
netctl does this for `_up`/`disk-guard`/`wireguard-guard`.

### 4e. The aggregate step-factory seam

An impl-less aggregate (a command with `depends_on` and no `impl`, #895) has no callable of its own -
the kernel synthesizes it at assembly time (#896). Your product only supplies a
`delivery.orchestrator.product.StepFactoryContext` (a **distinct** type from `delivery.context`'s
identity context - it carries a command-name → `Step` factory) via `assemble(step_context=…)`, so
`fooctl all` runs the `build → up` dependency plan out of the box:

```python
from delivery.orchestrator.product import StepFactoryContext

_MANIFEST = paths.CONTEXT.manifest()          # read once: assembled from, and resolved against

# a command NAME → a live-streamed `./fooctl.sh <cmd>` step, stamped with that command's dotted identity
_STEP_CONTEXT = StepFactoryContext.for_shim("fooctl", paths.ROOT / "fooctl.sh", _MANIFEST)

delivery_cli.assemble(app, _MANIFEST, product=paths.CONTEXT.name, step_context=_STEP_CONTEXT)
```

Add your own aggregates by declaring impl-less `depends_on` commands in the manifest - no product
Python per aggregate. Under the hood the synthesized callback calls
`delivery.orchestrator.product.run_command`, which expands the name through `Manifest.plan_for`
(transitive, deduped, each unique command once, in dependency order), maps each planned leaf through
your factory into a `Step`, wraps them in a `Pipeline` carrying the plan TREE (each node's own
`stop_on_failure` included), and dispatches through the shared TUI runner (headless fallback).
`step_context` is only required when the manifest declares aggregates; assembly fails loudly if one is
declared without it.

Your factory must stamp each `Step` with the leaf's exact-command identity (`command=`, the dotted
`group.command` path or the bare name). The kernel verifies the leaf-to-step pairing through it before it
trusts the tree for anything, and a step that names nothing is unverifiable: the whole tree is then
dropped, with a warning naming the offending step, back to a flat list and the single root-level
`stop_on_failure` — so a `lint → check-contract → test` chain runs on after a failure instead of stopping
(#42). `for_shim` does the stamping for you (`manifest.path_by_name(cmd)`, falling back to the bare name
for a command several groups own); write your own factory only for a step that is not a shim call, and
stamp it yourself.

> The type was named `ProductContext` before netctl#737 and collided with
> `delivery.context.ProductContext`; it is now `StepFactoryContext`, with a back-compat `ProductContext`
> alias kept until consumers migrate.

---

## 5. Dependencies: `-r` the kernel + add your own

Since #730 the kernel **owns** its dependency pins in `lib/platform/src/delivery/requirements.txt`
(typer, click, PyYAML, rich, textual, pydantic). Your product's `orchestrator/requirements.txt`
references it with `-r` and adds ONLY product deps, so a kernel bump lands in one place. The
scaffolder emits this out of the box (netctl#737) - it no longer re-pins the kernel deps inline:

```
# scaffolded orchestrator/requirements.txt (pip resolves the -r path relative to THIS file)
-r ../lib/platform/src/delivery/requirements.txt
# --- product-only deps ---
# add your own here
```

The `../` count matches the orchestrator-dir depth: the scaffold's `<root>/orchestrator/` layout uses
one `../`; netctl relocated the block three dirs below root under `deploy/provision/`, so its file
uses `-r ../../../lib/platform/src/delivery/requirements.txt` plus `requests` / `jinja2` /
`cryptography`. It is the one path to re-tune if you move the orchestrator dir (the Python side
self-locates via the marker-walk; this static pip file cannot). `launch.sh` runs
`pip install -r orchestrator/requirements.txt` into a host venv whenever the file is newer than its
stamp.

---

## 6. A minimal end-to-end "hello product" walkthrough

```bash
# 1. scaffold + vendor + verify, one command
./init-product.sh fooctl        # or by hand: PYTHONPATH=… python -m delivery.bootstrap fooctl + submodule add

# 2. run the generated CLI (first run bootstraps the host venv via launch.sh)
./fooctl.sh help              # lists: build (CI panel), deploy up/down/all (CD panel)
./fooctl.sh build             # → orchestrator.cli:build placeholder
./fooctl.sh up                # flat alias == `./fooctl.sh dev deploy up`
./fooctl.sh dev deploy up     # explicit env-first form
./fooctl.sh all               # runs the build → up dependency plan (the kernel-bound aggregate)
./fooctl.sh build --instance …  # ✗ agnostic group rejects an env / a non-env prefix
```

Now add a real command — say `deploy status`:

```yaml
# fooctl.yaml, under groups:
  deploy:
    up:     { impl: "orchestrator.cli:up",     help: "Deploy to the target environment." }
    down:   { impl: "orchestrator.cli:down",   help: "Tear the deployment down." }
    status: { impl: "orchestrator.cli:status", help: "Show the deployment status." }
```

```python
# orchestrator/cli.py — add the callable BEFORE the assemble() call
def status() -> None:
    """Show the deployment status."""
    log.info("fooctl: status of env %s", environments.current().name)
```

`./fooctl.sh dev deploy status` (and the flat `./fooctl.sh status`) now dispatch — no kernel change,
no re-registration. The manifest is the surface; `cli.py` holds the callables. The starter `all`
aggregate is already wired (`./fooctl.sh all` runs build → up); add your own aggregates the same way
(see §4e).

---

## Resolved: the four scaffolder gaps (netctl#737)

This guide originally surfaced four seams that were under-documented or not scaffolded in code. All
four are now fixed in the scaffolder, so a freshly bootstrapped product is correct out of the box:

- **Deps via `-r`, not inline pins.** `orchestrator/requirements.txt` now `-r`-references
  `lib/platform/src/delivery/requirements.txt` and carries only product deps (§5), so a new product
  follows the #730 convention from the start instead of re-pinning the six kernel deps.
- **The `all` aggregate is wired, not dead.** The scaffolded `cli.py` builds a `StepFactoryContext`
  and injects it via `assemble(step_context=…)`, and `deploy.all` in the manifest is an impl-less
  `depends_on: [build, up]` aggregate, so `fooctl all` runs the `build → up` pipeline through the
  shared runner (§4e).
- **Root detection is a marker-walk.** `paths.py` climbs to the directory holding `<product>.yaml`
  instead of a hardcoded `parents[N]`, so relocating the orchestrator dir needs no hand-edit (§4a).
  (The requirements `-r` `../` count stays layout-relative - a static pip file cannot self-locate.)
- **The `ProductContext` name clash is gone.** The step-factory type is now
  `StepFactoryContext` (a back-compat `ProductContext` alias remains until consumers migrate), distinct
  from the identity `delivery.context.ProductContext` (§4e).

One more of the same kind surfaced later, when biz-cockpit adopted the platform (#42): the scaffolded
step factory built its steps **without `command=`**, so the kernel could not verify the leaf-to-step
pairing and dropped every plan tree it was handed — silently voiding each subtree's `stop_on_failure`.
The scaffold now uses `StepFactoryContext.for_shim`, which stamps the identity (§4e), and a product that
was adopted against an older scaffold fixes it by switching its own factory to the same call.
