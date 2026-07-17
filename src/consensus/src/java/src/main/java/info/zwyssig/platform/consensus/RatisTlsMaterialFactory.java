package info.zwyssig.platform.consensus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Pure assembly of the E1c Ratis mTLS material (#171) from PEM strings: it turns an OpenBao-issued leaf
 * plus the E1b union truststore into a {@link javax.net.ssl.KeyManager}/{@link javax.net.ssl.TrustManager}
 * pair that Ratis can inject via {@code GrpcTlsConfig(KeyManager, TrustManager, mtls)}.
 *
 * <p>Two invariants matter:
 * <ul>
 *   <li>The KeyManager presents the FULL chain {@code [leaf, issuing_ca]}, never the leaf alone: the
 *       peer verifier holds only the env ROOT, so the presenter must ship its intermediate (E1b D4).</li>
 *   <li>The TrustManager trusts the UNION of the E1b anchors and the node's own local root, so a cold
 *       follower whose replicated E1b table is still empty can already validate peers from its local
 *       root (dissolving the truststore-over-Raft boot deadlock).</li>
 * </ul>
 *
 * <p>Everything here is static and framework-free (no product dependency, module rule holds): it takes
 * raw PEM strings, never a product certificate value object, so the shared assembly is consumed by E1c
 * (Ratis), E1d (sidecar) AND K2 (#323, the {@code /api} mTLS connector + client) from one place, and the
 * product adapts its own issued-certificate type to PEM at the call site.
 */
public final class RatisTlsMaterialFactory {

    /** rsaEncryption OID (1.2.840.113549.1.1.1) as a DER-encoded AlgorithmIdentifier body. */
    private static final byte[] RSA_OID = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
            0x0D, 0x01, 0x01, 0x01};
    private static final byte[] DER_NULL = {0x05, 0x00};
    /** The single key entry alias in the in-memory leaf keystore (also the {@code SslBundleKey} alias). */
    public static final String KEY_ALIAS = "node";
    /** The in-memory keystore/key password (no material ever touches disk). */
    public static final String KEYSTORE_PASSWORD = "netctl";
    private static final char[] IN_MEMORY_PASSWORD = KEYSTORE_PASSWORD.toCharArray();

    private RatisTlsMaterialFactory() {
    }

    /** Parse a single X.509 certificate from a PEM string (real newlines, {@code CertificateFactory}). */
    public static X509Certificate parseCertificate(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse X.509 certificate PEM", e);
        }
    }

    /**
     * Parse an RSA private key from PEM, accepting BOTH PKCS#8 ("PRIVATE KEY") and PKCS#1
     * ("RSA PRIVATE KEY", the OpenBao default for an RSA role). PKCS#1 is wrapped into PKCS#8 so plain
     * JCA (no BouncyCastle) can decode it.
     */
    static PrivateKey parseRsaPrivateKey(String pem) {
        try {
            byte[] pkcs8;
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                pkcs8 = wrapPkcs1AsPkcs8(decodePem(pem));
            } else if (pem.contains("BEGIN PRIVATE KEY")) {
                pkcs8 = decodePem(pem);
            } else {
                throw new IllegalArgumentException("unsupported private key PEM header (expected RSA/PKCS#8)");
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse RSA private key PEM", e);
        }
    }

    /**
     * Build the in-memory PKCS12 keystore holding {@code [leaf, issuing_ca]} + the issued private key under
     * {@link #KEY_ALIAS}/{@link #KEYSTORE_PASSWORD}. Exposed so K2's mTLS connector can hand the SAME
     * material to an {@code SslBundle} (which is keystore-backed) rather than only a {@link KeyManager}.
     * Takes raw PEM strings; the product adapts its issued-certificate value object at the call site.
     */
    public static KeyStore leafKeyStore(String certificatePem, String issuingCaCertificatePem, String privateKeyPem) {
        try {
            X509Certificate leaf = parseCertificate(certificatePem);
            X509Certificate issuingCa = parseCertificate(issuingCaCertificatePem);
            PrivateKey key = parseRsaPrivateKey(privateKeyPem);

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(KEY_ALIAS, key, IN_MEMORY_PASSWORD, new X509Certificate[]{leaf, issuingCa});
            return ks;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the leaf keystore", e);
        }
    }

    /**
     * Build a KeyManager that presents {@code [leaf, issuing_ca]} backed by the issued private key. The
     * returned manager is the raw JCA one; the caller wraps it in a {@link DynamicKeyManager} so a later
     * renewal (E2) is a material swap rather than a Ratis restart. Takes raw PEM strings.
     */
    public static X509ExtendedKeyManager keyManager(String certificatePem, String issuingCaCertificatePem, String privateKeyPem) {
        try {
            KeyStore ks = leafKeyStore(certificatePem, issuingCaCertificatePem, privateKeyPem);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, IN_MEMORY_PASSWORD);
            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509ExtendedKeyManager x) {
                    return x;
                }
            }
            throw new IllegalStateException("no X509ExtendedKeyManager produced from the leaf keystore");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the Ratis leaf KeyManager", e);
        }
    }

    /**
     * Compose the trust anchors: every certificate entry of the E1b {@code trustStore()} (may be null on
     * a cold boot) UNION the node's own local root PEM. Duplicates (the same encoded cert) are dropped so
     * the identical local root and E1b anchor collapse to one entry.
     */
    public static KeyStore unionTrustStore(KeyStore e1bTrustStore, String localRootPem) {
        try {
            KeyStore union = KeyStore.getInstance("PKCS12");
            union.load(null, null);
            List<X509Certificate> added = new ArrayList<>();

            if (e1bTrustStore != null) {
                Enumeration<String> aliases = e1bTrustStore.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    if (e1bTrustStore.isCertificateEntry(alias)
                            && e1bTrustStore.getCertificate(alias) instanceof X509Certificate cert) {
                        addIfAbsent(union, added, cert);
                    }
                }
            }
            addIfAbsent(union, added, parseCertificate(localRootPem));
            return union;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot compose the union truststore", e);
        }
    }

    /** Build a TrustManager (PKIX) from the given truststore; wrapped in a {@link DynamicTrustManager}. */
    public static X509ExtendedTrustManager trustManager(KeyStore trustStore) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager x) {
                    return x;
                }
            }
            throw new IllegalStateException("no X509ExtendedTrustManager produced from the union truststore");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the Ratis TrustManager", e);
        }
    }

    private static void addIfAbsent(KeyStore ks, List<X509Certificate> seen, X509Certificate cert) throws Exception {
        for (X509Certificate existing : seen) {
            if (existing.equals(cert)) {
                return;
            }
        }
        ks.setCertificateEntry("anchor-" + seen.size(), cert);
        seen.add(cert);
    }

    /** Strip the PEM armour and base64-decode the body (tolerates real newlines and CR/LF). */
    private static byte[] decodePem(String pem) {
        String body = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /**
     * Wrap a PKCS#1 {@code RSAPrivateKey} DER into a PKCS#8 {@code PrivateKeyInfo} DER:
     * {@code SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING(pkcs1) }}. Lets the
     * JDK KeyFactory decode an OpenBao-default RSA key without BouncyCastle.
     */
    private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algId = der(0x30, concat(RSA_OID, DER_NULL));
        byte[] pkOctet = der(0x04, pkcs1);
        return der(0x30, concat(version, algId, pkOctet));
    }

    /** Emit a DER TLV: one tag byte, a definite length, then the content. */
    private static byte[] der(int tag, byte[] content) {
        byte[] length = derLength(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    /** DER definite length: short form below 128, long form (0x80|n) above. */
    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        int byteCount = 0;
        for (int t = length; t > 0; t >>>= 8) {
            byteCount++;
        }
        byte[] out = new byte[1 + byteCount];
        out[0] = (byte) (0x80 | byteCount);
        for (int i = 0; i < byteCount; i++) {
            out[out.length - 1 - i] = (byte) (length >>> (8 * i));
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
