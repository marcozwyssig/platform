# Onboarding: how another project adopts the `delivery` platform

The `delivery` kernel is the product-agnostic shared core for the `*ctl` family (netctl =
network automation, infractl = IaaS/PaaS). It ships **mechanism only**: the manifest-driven CLI
assembly, the env-first dispatch, the step/composite runner + split-pane TUI, and the host-venv
launcher. Your product ships **data + a thin adapter**: one manifest, one shim, and a small
`orchestrator` package with your command implementations. The invariant the kernel is built around
is *"gleiche Maschine, anderer Katalog"* — same machine, different catalog.

This guide centers on `python -m delivery.bootstrap`, the scaffolder that writes a working
skeleton, and uses **netctl** (this repo, at `deploy/provision/orchestrator/`) as the worked
example. Every path and API below is real kernel code under
`lib/platform/src/delivery/src/python/delivery/`.

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
| Composite runner | `delivery.orchestrator.product.run_composite` + `steps` (`Step`/`Pipeline`/`dispatch`) + `tui` | Supply a command→`Step` factory |
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
orchestrator/requirements.txt                      # host-venv deps
orchestrator/src/python/orchestrator/
    __init__.py                                    # the product package
    __main__.py                                    # `python -m orchestrator` entry
    cli.py                                          # composition root: root Typer app + assemble + main
    paths.py                                        # ProductContext wiring (delivery.context)
    environments.py                                # EnvironmentProvider (backends + env-gate)
```

The package is **always** named `orchestrator` (mirroring netctl), so a hyphenated product slug never
has to be a Python identifier. Then, per the printed next-steps:

```bash
cd fooctl
git init
git submodule add https://github.com/marcozwyssig/platform.git lib/platform
./fooctl.sh help          # the assembled, manifest-driven CLI runs
```

`./fooctl.sh help` boots because the generated `paths.py` registers a `ProductContext` and the
generated manifest validates through `delivery.orchestrator.manifest.load` — the CLI is assembled
from data, not hand-written.

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

env_groups: [deploy]                               # the env-first CD groups (rest are agnostic)

composites:                                        # named, ordered command pipelines (#456)
  all:
    steps: [build, up]                             # each step names a command declared above
    stop_on_failure: false                         # default: run every step, take the worst rc

default: dev                                       # the env matrix (#15, folded into one manifest #651)
environments:
  dev: { backend: local, description: "Local development environment." }
```

**Command spec fields** (`CommandSpec`): `impl` (`"module:function"`, required), `help` (one-line
summary, required), and optional `passthrough_args: true` (forward unrecognised trailing args to an
underlying tool — netctl uses it on `accept` → pytest).

**`load()` validates loudly** (each violation is a `ValueError`):

1. `groups` is non-empty;
2. every `env_groups` entry names a declared group;
3. every spec has a non-empty `help` and a well-formed `module:function` `impl`;
4. every composite step names a real command.

Unknown **top-level** keys are ignored, which is where your product build data lives (netctl.yaml
carries `images:`, `volumes:`, `doctoolchain_version:`, `topology:` — read raw via
`ProductContext.manifest_data()`, never through the CLI engine).

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

# <root>/orchestrator/src/python/orchestrator/paths.py → root is FOUR parents up
_DERIVED_ROOT = Path(__file__).resolve().parents[4]
_DERIVED_MANIFEST = _DERIVED_ROOT / "fooctl.yaml"

CONTEXT = context.set_current(
    context.ProductContext.resolve("fooctl", _DERIVED_ROOT, _DERIVED_MANIFEST))
ROOT = CONTEXT.root
MANIFEST = CONTEXT.manifest_path
```

`.resolve()` lets the kernel env vars `DELIVERY_PRODUCT_ROOT` / `DELIVERY_MANIFEST` override the
derived defaults (a relocated checkout, a test fixture tree); with neither set the UX is byte-
identical. The `parents[N]` depth is **layout-dependent**: the scaffold uses `[4]`
(`<root>/orchestrator/…`); netctl relocated the block under `deploy/provision/orchestrator/`, so its
real `paths.py` uses `parents[6]` and reads the manifest at `parents[3]`. Adjust `N` if you move the
package.

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

# assemble at import: binds each manifest command's callback onto `app`
delivery_cli.assemble(app, paths.CONTEXT.manifest(), product=paths.CONTEXT.name)

def main() -> None:                           # `python -m orchestrator`
    delivery_cli.main(app=app, context=paths.CONTEXT,
                      environments=environments, aliases=_ALIASES)
```

You may register product-only internal commands on `app` with `@app.command` **before** `assemble`;
netctl does this for `_up`/`disk-guard`/`wireguard-guard`.

### 4e. The composite step-factory seam (only if you use composites)

Composites are declared as data but **not** auto-wired by `assemble`. To run one, build a
`delivery.orchestrator.product.ProductContext` (a **distinct** type from `delivery.context`'s — it
carries a command-name → `Step` factory) and call `run_composite`. netctl's factory (`steps.py`):

```python
from delivery.orchestrator.product import ProductContext
# a command NAME → a live-streamed `./netctl.sh <cmd>` step, labelled from the manifest
NETCTL_CONTEXT = ProductContext("netctl", lambda cmd: shell_step(manifest_label(cmd), [cmd]))
```

and a command invokes it:

```python
raise typer.Exit(product.run_composite("bringup", paths.CONTEXT.manifest(), steps.NETCTL_CONTEXT))
```

`run_composite` maps each step name through your factory into a `Step`, wraps them in a `Pipeline`
carrying `stop_on_failure`, and dispatches through the shared TUI runner (headless fallback).

---

## 5. Dependencies: `-r` the kernel + add your own

Since #730 the kernel **owns** its dependency pins in `lib/platform/src/delivery/requirements.txt`
(typer, click, PyYAML, rich, textual, pydantic). Your product's `orchestrator/requirements.txt`
references it with `-r` and adds ONLY product deps, so a kernel bump lands in one place. netctl's
file (the reference form):

```
# pip resolves the -r path relative to THIS file
-r ../../../lib/platform/src/delivery/requirements.txt
# --- product-only deps ---
requests==2.34.2
jinja2==3.1.4
cryptography==43.0.1
```

The `../` count is layout-dependent (netctl's `orchestrator/requirements.txt` sits three dirs below
root under `deploy/provision/`; the scaffold layout sits one dir below root, so it would use
`-r ../lib/platform/src/delivery/requirements.txt`). `launch.sh` runs
`pip install -r orchestrator/requirements.txt` into a host venv whenever the file is newer than its
stamp.

---

## 6. A minimal end-to-end "hello product" walkthrough

```bash
# 1. scaffold
PYTHONPATH=lib/platform/src/delivery/src/python python -m delivery.bootstrap fooctl
cd fooctl && git init
git submodule add https://github.com/marcozwyssig/platform.git lib/platform

# 2. run the generated CLI (first run bootstraps the host venv via launch.sh)
./fooctl.sh help              # lists: build (CI panel), deploy up/down (CD panel)
./fooctl.sh build             # → orchestrator.cli:build placeholder
./fooctl.sh up                # flat alias == `./fooctl.sh dev deploy up`
./fooctl.sh dev deploy up     # explicit env-first form
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
no re-registration. The manifest is the surface; `cli.py` holds the callables. To make the declared
`all` composite runnable, add a `steps.py` with a `product.ProductContext` factory and a command that
calls `run_composite("all", …)` (see §4e).

---

## Gaps found (seams under-documented / not scaffolded in code)

- **The scaffold re-pins deps inline; #730 says use `-r`.** `bootstrap._REQUIREMENTS` emits the six
  kernel pins verbatim into `orchestrator/requirements.txt` rather than `-r`-referencing
  `lib/platform/src/delivery/requirements.txt`. So a freshly bootstrapped product does NOT follow the
  current netctl convention and will drift from the kernel's pins. Convert it to the `-r` form after
  scaffolding.
- **The scaffolded `all` composite is declared but unreachable.** `bootstrap` emits
  `composites.all` in the manifest, but the generated `cli.py` wires no step factory and no
  `run_composite` call — `assemble`/`main` never touch composites. The composite-runner seam
  (`delivery.orchestrator.product.ProductContext` + a `steps.py`) must be added by hand; the
  scaffolder does not model it, and its own docstring lists a richer scaffolder as deferred.
- **`parents[N]` depth is a silent layout coupling.** Both `paths.py` (`parents[4]`) and the
  requirements `-r` path assume the scaffold's `<root>/orchestrator/…` layout. Relocating the package
  (as netctl did to `deploy/provision/orchestrator/`, needing `parents[6]`/`parents[3]`) requires
  hand-editing both, with no helper or check to catch a wrong count.
- **The two `ProductContext` types collide by name.** `delivery.context.ProductContext` (identity)
  and `delivery.orchestrator.product.ProductContext` (composite step factory) are distinct classes
  with the same name; only a docstring note disambiguates them. Easy to confuse when wiring §4e.
