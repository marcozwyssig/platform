# CLAUDE.md

Briefing for Claude Code working on **platform** — the product-agnostic shared core for the `*ctl`
family (netctl = network automation, infractl = IaaS/PaaS). Read this, then `README.md`.

## What this is

MECHANISM only: replication machinery (Raft wiring, mTLS, appliedIndex persistence), orchestrator
engine (steps runner + TUI), CLI assembly (manifest-driven), and process/run/interaction seams.
Products ship their own data models. The invariant: **"gleiche Maschine, anderer Katalog"** — no
product-specific data or models belong here. If a change requires product knowledge, it belongs in
the product repo, not here.

## HARD naming rule

Python import package: `platformcore`. NEVER `platform` — that name shadows the Python stdlib
module. Java group: `info.zwyssig.platform`. Both are enforced in existing code; do not widen.

## Layout

arc42 building blocks under `src/` (platform#2, mirrors netctl#548): each block owns code + tests,
language level inside the block, test LEVEL on top.

```
src/consensus/                     Java lib block
  src/java/                        Gradle module :consensus - Raft wiring, mTLS material,
                                   appliedIndex JPA persistence (standard src/main inside)
  test/unit/java/                  JUnit tests (+ resources/)
src/delivery/                      Python lib block
  src/python/platformcore/         Python shared core
    orchestrator/                  steps runner + TUI + manifest assembly
    (clitaxonomy, environments, degraded, disk, diskguard, host, interact, log, run, vcs, waits)
  test/unit/python/                pytest unit tests (conftest.py adds the block's src/python to
                                   sys.path)
```

## Consumption model

Both products vendor this repo as a **git submodule at `lib/platform`**:
- Python: `lib/platform/src/delivery/src/python` prepended to `sys.path` (or `PYTHONPATH`) by
  product conftest/shim.
- Java: Gradle composite `includeBuild("lib/platform")` in the product's settings; module name
  `:consensus` (group `info.zwyssig.platform`, artifact `consensus`).

Change discipline: **commit + push here FIRST**, then bump the submodule pointer in each consuming
product (separate commits in netctl and infractl). Breaking changes require a coordinated pointer
bump in both products.

## Java event-model decision (locked)

Platform ships an **open (non-sealed) `ReplicatedEvent` marker** as a generic bound. Each product
seals its own event catalog and binds `E` via `CatalogStateMachine<E extends ReplicatedEvent>`.
Exhaustiveness is product-side and load-bearing. Do not seal `ReplicatedEvent` here.

## Build and test

```
# Python (run from repo root)
python3 -m pytest src/delivery/test/unit/python -v

# Java (no wrapper checked in; use a local Gradle or the docker gradle:jdk25 image)
gradle :consensus:build             # compile + test the consensus module
gradle :consensus:test              # tests only
```

Java builds need Gradle 9 + JDK 25; Spring Boot 4.0.x, Ratis 3.1.3 (same versions as netctl's
infrastructure module — keep in sync). Tests use H2 in-memory, Liquibase changelog at
`src/consensus/src/java/src/main/resources/db/changelog/platform-raft-applied-index.xml`.

## Conventions

- All artifacts (code, comments, docs, commits) in English.
- Conventional commits matching the existing log style: `type(scope): summary`.
- AAA tests with goal-stating names, including negative cases.
- Constructor injection; no field injection.
- `JpaRaftAppliedIndexRepository` intentionally keeps `EntityManager` (native SQL, no JPA entity);
  all other new code uses Spring Data JPA.
