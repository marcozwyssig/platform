"""Manifest-driven CLI assembly for the CI/CD orchestrator: a per-product YAML manifest is the ONE
declarative source for the command taxonomy (groups + env-gating), each command's implementation
reference ("module:function") and its help summary. A product ships the manifest + the command impls;
this engine reads the manifest and the product assembles its CLI from it - product-specifics become
DATA, not code, which dissolves the platform -> product coupling.

The engine is PURE and framework-free: it parses + validates the manifest (loudly, like
platformcore.environments.parse), resolves an impl reference to the real callable, and builds the shared
CommandTaxonomy (the env-gate) from the manifest so that logic is reused, not copied. It deliberately
imports no CLI framework: registering Typer commands / sub-apps / help panels is the PRODUCT's job (the
engine must not bind to one CLI framework). "One source, two outputs": the same manifest assembles the
runtime CLI now and, in Phase 2, the CI workflows.
"""
from __future__ import annotations

import importlib
from typing import Callable, NamedTuple

import yaml

from platformcore.clitaxonomy import CommandTaxonomy


class CommandSpec(NamedTuple):
    """One command's declaration: its implementation reference and a one-line help summary.

    `impl` is a "module:function" reference (e.g. "netctl.cli:seed") the engine resolves to the callable
    that runs the command. `help` is the canonical short summary (used in listings + consumed by the
    Phase-2 CI-doc generation). `passthrough_args` marks a command that forwards any unrecognised trailing
    args to an underlying tool (e.g. a test runner) - a generic intent the product maps to its CLI
    framework's settings. The owning GROUP is deliberately NOT stored here: it is derivable from the
    manifest's `groups` map (the single membership source), so a command can never disagree with it.
    """
    impl: str
    help: str
    passthrough_args: bool = False


class Manifest(NamedTuple):
    """A parsed, validated product manifest: the command taxonomy (group -> its command names, and the
    subset of env-first groups) plus each command's spec. `groups` and `env_groups` already have the exact
    shape the shared CommandTaxonomy consumes, so `taxonomy()` builds the env-gate without duplicating its
    logic.
    """
    groups: dict[str, tuple[str, ...]]
    env_groups: frozenset[str]
    commands: dict[str, CommandSpec]

    def taxonomy(self) -> CommandTaxonomy:
        """The shared env-gate engine built from this manifest's taxonomy (one env-gate, not a copy)."""
        return CommandTaxonomy(self.groups, self.env_groups)


def _split_impl(impl: str, context: str) -> tuple[str, str]:
    """Split a "module:function" reference into its two parts, failing loudly on a malformed one.

    `context` names what is being validated (a command name, or the impl itself) so the error points at
    the offending declaration.
    """
    module, sep, function = impl.partition(":")
    if sep != ":" or not module or not function or ":" in function:
        raise ValueError(f"{context}: impl must be 'module:function', got '{impl}'")
    return module, function


def load(text: str) -> Manifest:
    """Parse a product manifest YAML into a validated Manifest (pure: no import of the impls, no Typer).

    Validates, failing loudly with ValueError so a bad manifest is caught here and not deep in the CLI:
      - `groups` maps each declared group to its ordered command names (the single membership source);
      - every `env_groups` entry is a declared group;
      - no command appears in more than one group;
      - every command listed in a group has a spec, and every spec's command is in exactly one group
        (membership and specs agree, both ways);
      - every spec declares a non-empty `help` and a well-formed "module:function" `impl`.
    Mirrors the style of platformcore.environments.parse.
    """
    data = yaml.safe_load(text) or {}

    raw_groups = data.get("groups") or {}
    if not raw_groups:
        raise ValueError("manifest defines no groups")
    groups: dict[str, tuple[str, ...]] = {
        str(name): tuple(str(m) for m in (members or ())) for name, members in raw_groups.items()
    }

    # env_groups: every entry must name a declared group.
    env_groups_names = [str(g) for g in (data.get("env_groups") or ())]
    for g in env_groups_names:
        if g not in groups:
            raise ValueError(f"env_groups entry '{g}' is not a declared group")
    env_groups = frozenset(env_groups_names)

    # reverse index; a command in two groups is a taxonomy error.
    command_group: dict[str, str] = {}
    for group, members in groups.items():
        for cmd in members:
            if cmd in command_group:
                raise ValueError(
                    f"command '{cmd}' is in more than one group ('{command_group[cmd]}' and '{group}')")
            command_group[cmd] = group

    raw_commands = data.get("commands") or {}
    commands: dict[str, CommandSpec] = {}
    for name, spec in raw_commands.items():
        name = str(name)
        spec = spec or {}
        impl = str(spec.get("impl", "")).strip()
        if not impl:
            raise ValueError(f"command '{name}': missing impl")
        _split_impl(impl, f"command '{name}'")   # validates the "module:function" shape
        help_text = str(spec.get("help", "")).strip()
        if not help_text:
            raise ValueError(f"command '{name}': missing help")
        commands[name] = CommandSpec(impl=impl, help=help_text,
                                     passthrough_args=bool(spec.get("passthrough_args", False)))

    # membership and specs must agree, both ways.
    missing_spec = sorted(c for c in command_group if c not in commands)
    if missing_spec:
        raise ValueError(f"commands declared in a group but missing a spec: {missing_spec}")
    orphan_spec = sorted(c for c in commands if c not in command_group)
    if orphan_spec:
        raise ValueError(f"commands with a spec but not in any declared group: {orphan_spec}")

    return Manifest(groups=groups, env_groups=env_groups, commands=commands)


def resolve_impl(spec: CommandSpec) -> Callable[..., object]:
    """Import the module named in the spec's `impl` and return its function - where the manifest's
    declarative "module:function" becomes the real callable the CLI runs. Raises a clear ValueError if the
    module cannot be imported or the function is missing (so a stale impl ref fails loudly, not silently).
    """
    module_name, function_name = _split_impl(spec.impl, f"impl '{spec.impl}'")
    try:
        module = importlib.import_module(module_name)
    except ImportError as exc:
        raise ValueError(f"impl '{spec.impl}': cannot import module '{module_name}': {exc}") from exc
    try:
        return getattr(module, function_name)
    except AttributeError as exc:
        raise ValueError(
            f"impl '{spec.impl}': module '{module_name}' has no attribute '{function_name}'") from exc
