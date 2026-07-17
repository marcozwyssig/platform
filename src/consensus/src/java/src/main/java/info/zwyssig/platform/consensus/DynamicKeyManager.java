package info.zwyssig.platform.consensus;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * An {@link X509ExtendedKeyManager} that forwards every call to a volatile backing manager. Ratis reads
 * the {@code GrpcTlsConfig} (hence the KeyManager) ONCE at build time; a running server only picks up
 * fresh key material if the manager it holds is dynamic. Swapping {@link #set(X509ExtendedKeyManager)}
 * lets a later leaf renewal (E2) take effect on new/reconnecting handshakes WITHOUT rebuilding the Ratis
 * transport. E1c builds it once at boot; the swap seam is present from day one.
 */
public final class DynamicKeyManager extends X509ExtendedKeyManager {

    private volatile X509ExtendedKeyManager delegate;

    public DynamicKeyManager(X509ExtendedKeyManager initial) {
        this.delegate = initial;
    }

    public void set(X509ExtendedKeyManager next) {
        this.delegate = next;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return delegate.getClientAliases(keyType, issuers);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return delegate.chooseClientAlias(keyType, issuers, socket);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return delegate.getServerAliases(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return delegate.chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return delegate.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return delegate.getPrivateKey(alias);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return delegate.chooseEngineClientAlias(keyType, issuers, engine);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        return delegate.chooseEngineServerAlias(keyType, issuers, engine);
    }
}
