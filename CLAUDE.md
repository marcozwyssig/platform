# CLAUDE.md

Briefing for Claude Code working on **platform** — the product-agnostic shared core for the `*ctl`
family (netctl = network automation, infractl = IaaS/PaaS). Read this, then `README.md`.

## What this is

MECHANISM only: replication machinery — Raft wiring, mTLS material, appliedIndex persistence.
Products ship their own data models. The invariant: **"gleiche Maschine, anderer Katalog"** — no
product-specific data or models belong here. If a change requires product knowledge, it belongs in
the product repo, not here.

**The Python delivery kernel is no longer here.** It moved out with its history and lives at
[github.com/marcozwyssig/simplon](https://github.com/marcozwyssig/simplon), where it is published to
PyPI as `simplon` and consumed with `pip install simplon` + `simplon init <name>`. Anything about the
orchestrator engine, CLI assembly, tasks or the `delivery` import package belongs in that repo, not
in this one. Whether what is left should still be called "platform" is a question for the consensus
work.

## HARD naming rule

Java group: `info.zwyssig.platform`. NEVER `platform` as a Python import package — that name shadows
the Python stdlib module; the kernel's package is `simplon`, in its own repo.

## Layout

arc42 building blocks under `src/` (platform#2, mirrors netctl#548): each block owns code + tests,
language level inside the block, test LEVEL on top.

```
src/consensus/                     Java lib block
  src/java/                        Gradle module :consensus - Raft wiring, mTLS material,
                                   appliedIndex JPA persistence (standard src/main inside)
  test/unit/java/                  JUnit tests (+ resources/)
```

## Consumption model

Both products vendor this repo as a **git submodule at `lib/platform`**, now for the Java side only:
Gradle composite `includeBuild("lib/platform")` in the product's settings; module name `:consensus`
(group `info.zwyssig.platform`, artifact `consensus`). The Python kernel is a PyPI dependency
(`simplon==<version>` in the product's own requirements) and is never on a source path.

Change discipline: **commit + push here FIRST**, then bump the submodule pointer in each consuming
product (separate commits in netctl and infractl). Breaking changes require a coordinated pointer
bump in both products.

## Java event-model decision (locked)

Platform ships an **open (non-sealed) `ReplicatedEvent` marker** as a generic bound. Each product
seals its own event catalog and binds `E` via `CatalogStateMachine<E extends ReplicatedEvent>`.
Exhaustiveness is product-side and load-bearing. Do not seal `ReplicatedEvent` here.

## Build and test

```
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
