package info.zwyssig.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.PrivateKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PEM private-key parsing for E1c: the OpenBao {@code netctl-node} role issues RSA keys and returns them
 * as PKCS#1 ("RSA PRIVATE KEY") by default, which plain JCA cannot decode directly. The parser must
 * accept BOTH PKCS#1 and PKCS#8 ("PRIVATE KEY") and yield the identical RSA key either way, so the
 * KeyManager can present the leaf regardless of which encoding the local OpenBao returns.
 */
class RsaPrivateKeyPemParseTest {

    @Test
    @DisplayName("parseRsaPrivateKey decodes a PKCS#1 RSA key (the OpenBao default) into an RSA PrivateKey")
    void parsesPkcs1Key() {
        // arrange: the leaf key in PKCS#1 traditional form
        String pkcs1 = TlsFixtures.read("tls/leafA.pkcs1.key.pem");

        // act
        PrivateKey key = RatisTlsMaterialFactory.parseRsaPrivateKey(pkcs1);

        // assert
        assertEquals("RSA", key.getAlgorithm(), "a PKCS#1 body must parse into an RSA key");
    }

    @Test
    @DisplayName("parseRsaPrivateKey decodes a PKCS#8 RSA key into an RSA PrivateKey")
    void parsesPkcs8Key() {
        // arrange: the same leaf key in PKCS#8 form
        String pkcs8 = TlsFixtures.read("tls/leafA.pkcs8.key.pem");

        // act
        PrivateKey key = RatisTlsMaterialFactory.parseRsaPrivateKey(pkcs8);

        // assert
        assertEquals("RSA", key.getAlgorithm(), "a PKCS#8 body must parse into an RSA key");
    }

    @Test
    @DisplayName("parseRsaPrivateKey yields the identical key from the PKCS#1 and PKCS#8 encodings of one key")
    void pkcs1AndPkcs8YieldTheSameKey() {
        // arrange: both encodings of the same leaf key
        PrivateKey fromPkcs1 = RatisTlsMaterialFactory.parseRsaPrivateKey(TlsFixtures.read("tls/leafA.pkcs1.key.pem"));
        PrivateKey fromPkcs8 = RatisTlsMaterialFactory.parseRsaPrivateKey(TlsFixtures.read("tls/leafA.pkcs8.key.pem"));

        // act + assert: both decode to the same PKCS#8 encoding (same private key)
        assertArrayEquals(fromPkcs8.getEncoded(), fromPkcs1.getEncoded(),
                "the PKCS#1 and PKCS#8 forms of one key must decode identically");
    }
}
