package info.zwyssig.platform.consensus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the pre-generated PKI PEM fixtures under {@code resources/tls/} for the E1c Ratis mTLS material
 * tests. The fixtures are a real two-tier PKI (rootA -> intA -> leafA) plus a wrong-env pair
 * (rootB -> leafB), generated offline with openssl, because plain JCA cannot sign X.509 certificates and
 * the module carries no BouncyCastle. Env A mirrors the OpenBao chain [leaf, issuing_ca, root].
 */
final class TlsFixtures {

    private TlsFixtures() {
    }

    static String read(String classpath) {
        try (InputStream in = TlsFixtures.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("missing test fixture on classpath: " + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("cannot read fixture " + classpath, e);
        }
    }
}
