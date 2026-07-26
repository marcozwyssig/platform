# Topology-manifest feasibility & gap analysis (netctl#731 Strand 1)

**Question the strand answers.** Can a *clean, generic* topology manifest express netctl's containerlab
mesh, or does the schema become secretly netctl-shaped (the epic's stated key risk: "a leaky abstraction
that costs the genericity it claims")?

**Verdict.** Yes at the schema level, with a caveat that reframes the epic. The generic schema
(`delivery.topology`) holds netctl's full mesh - all 50 clab nodes, 57 links, 4 OOB bridges, the mgmt
network, the mixed FRR/IOL/VyOS vendor set and #453 instance-scoping - and validates it, with **zero
netctl-specific schema fields** (proven: `deploy/env/dev/topology.yml` loads green, 8 netctl assertions +
24 platform schema tests). The netctl-shapedness is fully quarantined into three *data* indirections
(`vendor_kinds` values, `role`/`config_template` label values, and free-form `attributes` bags) - the
schema itself stays product-agnostic. **The abstraction does not leak.**

**But** the manifest sits at the containerlab *output* altitude. It expresses the *rendered* mesh, not
netctl's compact `lab.yml`. `lab.yml` is ~185 lines that `generate.py` (1136 lines) *derives* into a
1106-line clab topology + FRR/IOS/VyOS configs + the seed - roughly **6:1 compression on the clab alone**.
That derivation (address policy, role->node-set expansion, service->NIC placement, per-vendor config baking,
WireGuard keygen) is ~830 lines of `build_context` and it does **not** move into a generic schema. So the
real finding is not "the schema leaked" but "the schema cannot *compress* netctl's mesh; the compiler that
does the compressing stays product-side." That distinction decides how the remaining strands are scoped
(below).

---

## What labgen actually is: a derivation engine, not a template engine

`generate.py` consumes `lab.yml` and *derives* everything from each site's numeric `id` and a handful of
base prefixes. It is not filling a template with data; it is **compiling a compact mesh description into a
concrete lab**. Concretely, one `lab.yml` site (a name, an id, and 2-3 device rows) expands into 8-14 clab
nodes:

| lab.yml input (per site) | generate.py output (clab nodes) |
| --- | --- |
| `name`, `id`, a few flags | `postgres-<s>`, `openbao-<s>`, `keycloak-<s>`, `sidecar-<s>`, `netctl-<s>` (controller) |
| `devices: [{role, platform}]` | `l3s-<s>1`, `w3s-<s>1`, `ogw-<s>` (+ optional `l3s-<s>3` VyOS, `l3s-<s>2` cascade) |
| `data_subnet`, service rows | `client-<s>`, `client-<s>-l2a/l2b/l3/vyos`, `client-<s>2` |
| (implicit) | `oob-<s>` bridge |

The addressing is **derived, not declared**: `_did` (device id `1..2N`), `_link_octet` (the transit /30's
4th octet), the conform `/24` (`base | id<<shift`), the `0x800`/`0x900` transit blocks for the VyOS and OGW
uplinks, the `fd00:c71` ULA plan, the WireGuard `/24`s, the `mgmt_base_step` per-site block. None of this is
generic subnetting - it encodes **netctl's addressing policy**.

So the honest split of labgen is *not* the epic's "generic engine + product data". It is:

- **Generic renderer (~15%)**: take a concrete node/link model and emit `*.clab.yml` (the Jinja step),
  mint WireGuard X25519 keypairs, apply the #453 instance token/offset, wire the mgmt net + OOB bridges.
- **Product compiler (~85%)**: the ~830-line `build_context` that *derives* that node/link model (and the
  per-vendor configs + seed) from `lab.yml`.

Strand 1's schema is the interface *between* those two halves. That is exactly why it is clean: it is the
renderer's **input contract**, and the renderer is the generic part.

---

## The schema (`delivery.topology`) and where the design absorbs pressure

A generic containerlab topology: `mgmt` network, a `vendor_kinds` registry, `sites` (id-bearing groupings),
`nodes` (clab `kind`/`image`/`env`/`binds`/`exec`/`ports`/`mgmt_ipv4`/`startup_config` + a `config_template`
ref + `wait_for`), `links` (`node:iface` endpoint pairs), first-class `bridges`, and an `instance_scoping`
policy. Fail-loud: `extra="forbid"` catches typos, cross-object rules reject dangling site/vendor/endpoint
references and unresolvable kinds, and `load()` re-raises Pydantic errors as plain `ValueError` (mirrors
`delivery.orchestrator.manifest`).

Three indirections keep product-specificity out of the schema and in the *data*:

1. **`vendor_kinds`** - `frr -> linux/netctl-frr:local`, `ios_l2 -> cisco_iol type l2`,
   `vyos -> vyosnetworks_vyos`. The vendor set is a registry, so mixed vendors are data, not schema.
2. **`config_template`** - an opaque per-node ref (`frr/underlay.sh`, `ios/l3s-zh1.cfg`,
   `frr/ogw-zh-underlay.sh`) the product's config engine renders. The schema never parses it.
3. **`attributes`** on `Site`/`Node` - the free-form bag for product knobs (`http_port`,
   `keycloak_enabled`, `data_subnet`, `cascade`, `monitoring`, `config_delivery: netmiko-post-boot`, ...).

This is the anti-leak boundary: anything netctl-specific is a *value*, never a *field*.

---

## Capability-by-capability: generic (clean) vs leaky (netctl-shaped)

For each thing labgen needs, whether the schema expresses it cleanly or only via a netctl-shaped path:

| Capability labgen needs | Schema expresses it | Verdict |
| --- | --- | --- |
| Node roster (kind, image, mgmt-ip, env, binds, exec, ports, cmd, healthcheck) | Generic `Node` fields | **Clean** |
| Links as `node:iface` endpoint pairs | Generic `Link` | **Clean** |
| OOB bridges + veth-endpoint wiring | First-class `Bridge`, endpoint = bridge name | **Clean** |
| mgmt network name + subnet | `MgmtNetwork` (CIDR-validated) | **Clean** |
| Mixed vendors FRR / Cisco IOL / VyOS | `vendor_kinds` registry (data) | **Clean** |
| #453 instance-scoping (token infix + subnet offset) | `InstanceScoping` policy | **Clean (policy); engine performs it** |
| Per-node config artifact (FRR/IOS/VyOS) | `config_template` ref / `startup_config` | **Clean ref; render is product-side** |
| Sites as groupings with a numeric id | `Site` (id + `attributes`) | **Clean** |
| Structural roles l3s / w3s / ogw / controller | `role` opaque label (data) | **Soft leak** (label is netctl-semantic; engine must not branch on it) |
| Per-site product knobs (http_port, keycloak, cascade, monitoring, data_subnet) | `attributes` bag (data) | **Soft leak** (opaque to a generic engine; any behaviour on them stays product-side) |
| Address derivation (transit /30, conform /24, ULA, wg /24, mgmt blocks) | **Not expressible as data** - every address is a *literal* in the manifest | **Hard leak** (the compression lives in the product compiler) |
| WireGuard keypairs + baked peer table | Only the resulting `binds`/env are data; keygen + peer baking are behaviour | **Hard leak** (keygen mechanism is generic and movable; the *baking* is product logic) |
| Service->client-NIC placement (seg100 eth4, auto-l3 eth6, local-l2 eth7/8, ...) | Only the resulting nodes/links are data | **Hard leak** (the placement rule is netctl domain: overlays/VNI/VRF) |
| clab `stages` (create/wait-for, exec-on-enter, ...) | Simplified to `wait_for: [node]` (assumes stage=healthy) | **Lossy simplification** |

The "hard leaks" share one root cause: **a generic manifest can hold the derived *result* but not the
*derivation*.** To express the mesh generically you must enumerate every node, every link and every derived
address as a literal - i.e. the manifest *is* the rendered output. That is why `deploy/env/dev/topology.yml`
is ~300 lines of enumerated nodes/links where `lab.yml` is ~185 lines of compact rules: **genericity costs
the 6:1 compression.**

---

## The leak inventory (explicit)

1. **No address derivation.** Every mgmt IP is a literal; every underlay/transit/conform/ULA/wg address
   would be a literal inside `exec` bodies. The manifest cannot say "derive site 2's transit /30". The whole
   `_did`/`_link_octet`/conform/`0x800`/`0x900`/`fd00:c71` policy stays in the product. *(Root leak.)*
2. **No role->node-set expansion.** The manifest lists 46 concrete nodes; it cannot say "a site is
   postgres+openbao+keycloak+sidecar+controller+leaf+spine+ogw+clients". That expansion is netctl's
   deployment shape and stays in the compiler.
3. **No service->topology coupling.** netctl's overlay services (seg100/local-l2/local-l3/vyos-l3/data/
   auto-l3) *place* client NICs and access ports (`eth4`/`eth6`/`eth7`/`eth8`, cascade subifs). The manifest
   holds the resulting ports but not the rule; that rule is domain logic (VNI/VRF/conform).
4. **`role` values are netctl-semantic.** `l3s`/`w3s`/`ogw` are opaque strings to the schema, but a
   renderer that ever branched on them would re-import netctl semantics. Kept as inert labels; the discipline
   ("engine must not switch on `role`") is a *convention*, not enforced by the type.
5. **`attributes` bags are opaque to a generic engine.** `keycloak_enabled`, `cascade`, `monitoring`,
   `structural_platform` are data, but anything that *acts* on them (render a keycloak node, wire a cascade
   leaf) is product code. The bag quarantines the leak; it does not remove the behaviour.
6. **WireGuard peer-table baking.** X25519 keygen is generic and movable; deriving each node's baked peer
   list (public key + endpoint + wg-ip, keyed by `_did`) is product logic. The manifest only references the
   resulting private-key `binds`.
7. **Per-vendor config rendering is entirely product-side.** `config_template` names an artifact; producing
   it (FRR vtysh vs IOS startup-config vs VyOS post-boot netmiko) is netctl. The VyOS leaf even has *no*
   config artifact (clab replaces its config and bricks netmiko), captured only as
   `attributes.config_delivery: netmiko-post-boot`.
8. **`stages` simplification.** Modeled as `wait_for: [node]` (stage=healthy). Real clab stages are richer;
   a product needing exec-on-enter or non-healthy gates would need the field widened.

None of 1-8 is a *schema* leak - the schema stays generic. They are all cases where **behaviour that
`generate.py` performs cannot be reduced to declarative data**, so it remains in a product-side compiler.

---

## Is a generic topology manifest viable? Yes - if scoped as the renderer's input, not netctl's source

- **As the generic clab renderer's input contract: viable and clean.** Proven end-to-end here. A
  `clab-from-topology` engine in `delivery` (Strand 2) consuming this schema would render `*.clab.yml` for
  *any* product that produces a manifest, and a deliberately-different second topology (Strand 4) would
  render with zero netctl code. This is the epic's genuine prize.
- **As netctl's hand-authored source (the epic's "netctl = app + two manifests"): not without a real
  tradeoff.** The topology manifest is the *rendered* mesh. If netctl adopts it as the checked-in source, it
  gives up derive-from-site-id: `topology.yml` becomes a ~300-line hand-maintained file (every address a
  literal) instead of ~185 lines of rules, and adding a 5th site means hand-writing ~12 nodes + ~14 links +
  ~15 addresses instead of one `lab.yml` block. That is a regression in maintainability.

The realistic end-state is therefore **`netctl = app + a mesh-compiler (the surviving ~830-line
`build_context`, product-side) that emits the topology manifest + netctl.yaml`**, with the *renderer* and
*lifecycle* generic. The manifest is an **intermediate artifact** (compiler output / renderer input), not a
hand-authored source. That still delivers the epic's core value (any product gets a clab renderer + lifecycle
for free) without pretending netctl's derivation disappears.

---

## Cost of the remaining strands (informing whether the epic proceeds)

- **Strand 2 - extract the clab-render engine to `delivery`: MODERATE, worthwhile.** Move the generic 15%:
  (a) a manifest-driven Jinja clab renderer (the current `netctl.clab.yml.j2` is close but references
  netctl node structure; a generic template driven purely by the schema is new work), (b) WG X25519 keygen
  (~7 lines, trivial), (c) instance-scoping token/offset (`orchestrator.paths` is already semi-generic).
  netctl's `generate.py` is refactored into *compiler* (`lab.yml -> TopologyManifest`) + a call to the
  generic renderer. **Hard gate:** the byte-for-byte golden (`test_generate_instance.py`) must survive the
  two-stage render - that is the real risk and the real proof.
- **Strand 3 - genericize the lifecycle (`lab.py`/`labnet`/`rescue`/`uppipe`): LOW-MODERATE.** The #730
  orchestrator audit already found ops extraction ~86% done; clab `deploy/destroy/up/down` are generic
  containerlab ops parameterized by the rendered topology. Mechanical once the topology path is the
  interface. Lower risk than Strand 2.
- **Strand 4 - a second, non-netctl topology (the anti-leak test): LOW cost, HIGH value.** Authoring a
  small different topology (e.g. a 3-node linear lab, no EVPN/wg) and rendering it with the same kernel is
  the decisive proof the schema+renderer are not netctl-shaped. **Recommend doing Strand 4 immediately after
  Strand 2**, before Strand 3, so genericity is proven before more code moves.

**Recommendation: proceed, with the end-state re-scoped.** Keep Strands 2-4; treat the topology manifest as
the renderer's input contract (an intermediate artifact netctl's compiler emits), not as netctl's
hand-authored source. Do Strand 4 right after Strand 2. Do **not** adopt the manifest as netctl's checked-in
lab source - that trades the 6:1 `lab.yml` compression for hand-maintained literals with no offsetting gain.

---

## Evidence / reproduce

- Schema: `src/delivery/src/python/delivery/topology.py`; tests
  `src/delivery/test/unit/python/test_topology.py` (24 cases). Full delivery suite: **223 passed** (199
  baseline + 24 new).
- netctl sample manifest: `deploy/env/dev/topology.yml` (46 nodes + 4 bridges + 57 links, mirrors the
  committed golden `netctl.clab.yml`); oracle test
  `deploy/provision/labgen/test/unit/python/test_topology_manifest.py` (8 cases). labgen unit root: **51
  passed** (the byte-for-byte golden is untouched - the live render path is unchanged).
- Source studied: `deploy/provision/labgen/src/python/labgen/generate.py` (1136 lines),
  `deploy/env/dev/lab.yml` (185 lines), the golden `netctl.clab.yml` (1106 lines, 50 nodes / 57 links).
