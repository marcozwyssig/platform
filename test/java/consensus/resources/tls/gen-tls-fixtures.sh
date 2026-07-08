#!/usr/bin/env bash
#
# gen-tls-fixtures.sh - reproducibly (re)generate the RatisTlsMaterialFactoryTest PKI fixtures.
#
# WHY THIS EXISTS
#   The original fixtures (ad-hoc for #344) were signed with a 72h leaf validity: leafA.pem/leafB.pem
#   went notAfter "Jul 7 07:38 2026 GMT" and expired the moment the clock passed it, turning
#   RatisTlsMaterialFactoryTest > trustManagerTrustsEnvALeaf into a deterministic
#   CertificateExpiredException (Docker unit gate: 157 tests, 1 failed - see #431). No generation
#   script was committed and the root/intermediate keys were never committed, so the leaf could not
#   be re-signed - the whole PKI had to be regenerated. This script is that reproducible generator
#   and it stamps a ~100 year validity (36500 days) so the fixtures never become a TTL time-bomb again.
#
# WHAT IT PRODUCES (all beside this script, the 7 files RatisTlsMaterialFactoryTest asserts on)
#   rootA.pem              self-signed env-A root CA          Subject CN contains "root-ca"
#   intA.pem               env-A issuing CA, signed by rootA  Subject CN contains "issuing-ca"
#   leafA.pem              env-A leaf, signed by intA          Subject CN=zh (RSA key)
#   leafA.pkcs1.key.pem    leafA private key, PKCS#1           -----BEGIN RSA PRIVATE KEY-----
#   leafA.pkcs8.key.pem    the SAME leafA key, PKCS#8          -----BEGIN PRIVATE KEY-----
#   rootB.pem              self-signed env-B root CA (wrong env)
#   leafB.pem              env-B leaf, signed DIRECTLY by rootB (must be REJECTED by the env-A truststore)
#
#   The root/intermediate PRIVATE keys are deliberately NOT emitted (generated in a temp dir and shredded),
#   mirroring the OpenBao model: only the issued leaf key is held by the node.
#
# HOW TO RUN
#   test/java/unit/infrastructure/resources/tls/gen-tls-fixtures.sh
#   Requires OpenSSL 3.x (-addext, rsa -traditional, pkcs8 -topk8). Re-running overwrites
#   the 7 fixtures in place; the cert STRUCTURE is deterministic, only the random keys differ per run.
#
set -euo pipefail

DAYS=36500          # ~100 years - no realistic expiry, kills the 72h time-bomb
BITS=2048           # RSA 2048: above the JDK certpath/TLS minimum; keeps the "RSA" KeyManager alias
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- extension files for the CA-signed certs (explicit; no reliance on openssl.cnf defaults) ---------
# (the self-signed roots carry their extensions inline via -addext below)
cat > "$WORK/issuing.ext" <<'EOF'
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF

cat > "$WORK/leafA.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
subjectAltName=IP:10.0.0.1
EOF

cat > "$WORK/leafB.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF

# --- env A: rootA -> intA -> leafA(CN=zh) -------------------------------------------------------------
# rootA: self-signed root CA (CN contains "root-ca")
openssl genrsa -out "$WORK/rootA.key" "$BITS" 2>/dev/null
openssl req -new -x509 -key "$WORK/rootA.key" -sha256 -days "$DAYS" \
  -subj "/CN=netctl-dev-root-ca" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "subjectKeyIdentifier=hash" \
  -out "$HERE/rootA.pem"

# intA: issuing CA signed by rootA (CN contains "issuing-ca"); committed ALONE (intermediate only)
openssl genrsa -out "$WORK/intA.key" "$BITS" 2>/dev/null
openssl req -new -key "$WORK/intA.key" -sha256 -subj "/CN=netctl-zh-issuing-ca" -out "$WORK/intA.csr"
openssl x509 -req -in "$WORK/intA.csr" -CA "$HERE/rootA.pem" -CAkey "$WORK/rootA.key" \
  -CAserial "$WORK/rootA.srl" -CAcreateserial -sha256 -days "$DAYS" \
  -extfile "$WORK/issuing.ext" -out "$HERE/intA.pem"

# leafA: RSA leaf signed by intA (CN=zh); committed ALONE (leaf only, not a chain)
openssl genrsa -out "$WORK/leafA.key" "$BITS" 2>/dev/null
openssl req -new -key "$WORK/leafA.key" -sha256 -subj "/CN=zh" -out "$WORK/leafA.csr"
openssl x509 -req -in "$WORK/leafA.csr" -CA "$HERE/intA.pem" -CAkey "$WORK/intA.key" \
  -CAserial "$WORK/intA.srl" -CAcreateserial -sha256 -days "$DAYS" \
  -extfile "$WORK/leafA.ext" -out "$HERE/leafA.pem"

# leafA private key in BOTH encodings (identical key, two on-disk forms the SUT must both parse).
# OpenSSL 3.x genrsa emits PKCS#8, so PKCS#1 is produced explicitly with `rsa -traditional`.
openssl rsa -in "$WORK/leafA.key" -traditional -out "$HERE/leafA.pkcs1.key.pem" 2>/dev/null   # PKCS#1
openssl pkcs8 -topk8 -nocrypt -in "$WORK/leafA.key" -out "$HERE/leafA.pkcs8.key.pem"           # PKCS#8

# --- env B: rootB -> leafB (wrong env; leafB alone must FAIL against the env-A truststore) -----------
openssl genrsa -out "$WORK/rootB.key" "$BITS" 2>/dev/null
openssl req -new -x509 -key "$WORK/rootB.key" -sha256 -days "$DAYS" \
  -subj "/CN=netctl-prod-root-ca" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "subjectKeyIdentifier=hash" \
  -out "$HERE/rootB.pem"

openssl genrsa -out "$WORK/leafB.key" "$BITS" 2>/dev/null
openssl req -new -key "$WORK/leafB.key" -sha256 -subj "/CN=rogue" -out "$WORK/leafB.csr"
openssl x509 -req -in "$WORK/leafB.csr" -CA "$HERE/rootB.pem" -CAkey "$WORK/rootB.key" \
  -CAserial "$WORK/rootB.srl" -CAcreateserial -sha256 -days "$DAYS" \
  -extfile "$WORK/leafB.ext" -out "$HERE/leafB.pem"

# --- self-check: fail loudly if any invariant the test relies on is broken ----------------------------
fail() { echo "gen-tls-fixtures: FAILED - $*" >&2; exit 1; }

grep -q 'BEGIN RSA PRIVATE KEY' "$HERE/leafA.pkcs1.key.pem" || fail "leafA.pkcs1.key.pem is not PKCS#1"
grep -q 'BEGIN PRIVATE KEY'     "$HERE/leafA.pkcs8.key.pem" || fail "leafA.pkcs8.key.pem is not PKCS#8"
[ "$(openssl rsa -in "$HERE/leafA.pkcs1.key.pem" -noout -modulus 2>/dev/null)" \
  = "$(openssl rsa -in "$HERE/leafA.pkcs8.key.pem" -noout -modulus 2>/dev/null)" ] \
  || fail "pkcs1 and pkcs8 leafA keys differ"
openssl x509 -in "$HERE/rootA.pem" -noout -subject | grep -q 'root-ca'    || fail "rootA CN lacks root-ca"
openssl x509 -in "$HERE/intA.pem"  -noout -subject | grep -q 'issuing-ca' || fail "intA CN lacks issuing-ca"
openssl x509 -in "$HERE/leafA.pem" -noout -subject | grep -q 'CN=zh'      || fail "leafA CN is not zh"
openssl verify -CAfile "$HERE/rootA.pem" -untrusted "$HERE/intA.pem" "$HERE/leafA.pem" >/dev/null \
  || fail "leafA does not chain to rootA via intA"
openssl verify -CAfile "$HERE/rootA.pem" "$HERE/leafB.pem" >/dev/null 2>&1 \
  && fail "leafB unexpectedly verifies against rootA (must be rejected)"

echo "gen-tls-fixtures: OK - leafA notAfter: $(openssl x509 -in "$HERE/leafA.pem" -noout -enddate | cut -d= -f2)"
