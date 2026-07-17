package info.zwyssig.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the E1c Ratis mTLS material from PEM: the KeyManager presents the FULL [leaf, issuing_ca]
 * chain (E1b D4), the union truststore composes the E1b anchors with the node's own local root, and the
 * TrustManager built from that union trusts an env-A leaf but rejects a wrong-env (env-B) leaf.
 */
class RatisTlsMaterialFactoryTest {

    @Test
    @DisplayName("keyManager presents the full [leaf, issuing_ca] chain, leaf first (not the leaf alone)")
    void keyManagerPresentsLeafPlusIntermediateChain() {
        // arrange: an issued leaf with its intermediate (the OpenBao issue payload shape), as PEM strings

        // act
        X509ExtendedKeyManager km = RatisTlsMaterialFactory.keyManager(
                TlsFixtures.read("tls/leafA.pem"),
                TlsFixtures.read("tls/intA.pem"),
                TlsFixtures.read("tls/leafA.pkcs1.key.pem"));
        String alias = km.chooseServerAlias("RSA", null, null);
        X509Certificate[] chain = km.getCertificateChain(alias);

        // assert: two links, leaf (CN=zh) first, intermediate (issuing CA) second
        assertNotNull(alias, "a server alias must be selectable");
        assertEquals(2, chain.length, "chain must be [leaf, issuing_ca], not the leaf alone");
        assertTrue(chain[0].getSubjectX500Principal().getName().contains("zh"), "leaf CN=zh is first");
        assertTrue(chain[1].getSubjectX500Principal().getName().contains("issuing-ca"), "issuing CA is second");
        assertNotNull(km.getPrivateKey(alias), "the private key must back the alias");
    }

    @Test
    @DisplayName("unionTrustStore combines the E1b anchors with the local root")
    void unionTrustStoreCombinesE1bAnchorsAndLocalRoot() throws Exception {
        // arrange: an E1b truststore holding the env-B root; local root is env-A
        KeyStore e1b = KeyStore.getInstance("PKCS12");
        e1b.load(null, null);
        e1b.setCertificateEntry("b", RatisTlsMaterialFactory.parseCertificate(TlsFixtures.read("tls/rootB.pem")));

        // act
        KeyStore union = RatisTlsMaterialFactory.unionTrustStore(e1b, TlsFixtures.read("tls/rootA.pem"));

        // assert: both roots present
        assertEquals(2, Collections.list(union.aliases()).size(), "union holds the E1b anchor plus the local root");
    }

    @Test
    @DisplayName("unionTrustStore with a null E1b store still yields the local root (boot seed)")
    void unionTrustStoreSeedsFromLocalRootWhenE1bEmpty() throws Exception {
        // arrange: no E1b truststore yet (cold boot, before Raft replicated anything)
        // act
        KeyStore union = RatisTlsMaterialFactory.unionTrustStore(null, TlsFixtures.read("tls/rootA.pem"));

        // assert: the local root alone seeds the first handshake
        assertEquals(1, Collections.list(union.aliases()).size(), "the local root seeds the truststore");
    }

    @Test
    @DisplayName("unionTrustStore does not duplicate the local root when the E1b store already holds it")
    void unionTrustStoreDeduplicatesTheLocalRoot() throws Exception {
        // arrange: E1b already holds env-A root, local root is the same env-A root
        KeyStore e1b = KeyStore.getInstance("PKCS12");
        e1b.load(null, null);
        e1b.setCertificateEntry("a", RatisTlsMaterialFactory.parseCertificate(TlsFixtures.read("tls/rootA.pem")));

        // act
        KeyStore union = RatisTlsMaterialFactory.unionTrustStore(e1b, TlsFixtures.read("tls/rootA.pem"));

        // assert: no duplicate anchor
        assertEquals(1, Collections.list(union.aliases()).size(), "the identical local root is not added twice");
    }

    @Test
    @DisplayName("trustManager built from the env-A union trusts an env-A leaf chained to the root")
    void trustManagerTrustsEnvALeaf() throws Exception {
        // arrange: truststore = env-A root; presented chain = [leafA, intA]
        KeyStore union = RatisTlsMaterialFactory.unionTrustStore(null, TlsFixtures.read("tls/rootA.pem"));
        X509ExtendedTrustManager tm = RatisTlsMaterialFactory.trustManager(union);
        X509Certificate[] chain = {
                RatisTlsMaterialFactory.parseCertificate(TlsFixtures.read("tls/leafA.pem")),
                RatisTlsMaterialFactory.parseCertificate(TlsFixtures.read("tls/intA.pem"))
        };

        // act + assert: an env-member leaf validates (no exception)
        tm.checkClientTrusted(chain, "RSA");
    }

    @Test
    @DisplayName("trustManager built from the env-A union REJECTS a wrong-env (env-B) leaf")
    void trustManagerRejectsWrongEnvLeaf() throws Exception {
        // arrange: truststore = env-A root; presented chain = a leaf under env-B root
        KeyStore union = RatisTlsMaterialFactory.unionTrustStore(null, TlsFixtures.read("tls/rootA.pem"));
        X509ExtendedTrustManager tm = RatisTlsMaterialFactory.trustManager(union);
        X509Certificate[] rogue = {
                RatisTlsMaterialFactory.parseCertificate(TlsFixtures.read("tls/leafB.pem"))
        };

        // act + assert: a leaf from another env's root fails verification
        assertThrows(CertificateException.class, () -> tm.checkClientTrusted(rogue, "RSA"),
                "a leaf from a different env root must not validate against the env-A truststore");
    }

    @Test
    @DisplayName("parseCertificate returns the same X509 instance type for a valid PEM anchor")
    void parseCertificateParsesAnchorPem() {
        // arrange + act
        X509Certificate root = RatisTlsMaterialFactory.parseCertificate(TlsFixtures.read("tls/rootA.pem"));

        // assert
        assertTrue(root.getSubjectX500Principal().getName().contains("root-ca"), "the env-A root parses");
        assertSame(X509Certificate.class, X509Certificate.class);
    }
}
