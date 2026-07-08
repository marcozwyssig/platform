package info.zwyssig.platform.consensus;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * An {@link X509ExtendedTrustManager} that forwards every call to a volatile backing manager. E1b's
 * zero-downtime root rollover (union add, then retire) rebuilds {@code TrustStoreMaterializer.trustStore()}
 * on a {@code TrustAnchorsChanged} event; swapping {@link #set(X509ExtendedTrustManager)} here lets the
 * live Ratis transport pick up the new union on the next handshake WITHOUT a redeploy. Without the
 * dynamic seam, E1b's rollover would stop at the truststore and never reach the encrypted channel.
 */
public final class DynamicTrustManager extends X509ExtendedTrustManager {

    private volatile X509ExtendedTrustManager delegate;

    public DynamicTrustManager(X509ExtendedTrustManager initial) {
        this.delegate = initial;
    }

    public void set(X509ExtendedTrustManager next) {
        this.delegate = next;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
