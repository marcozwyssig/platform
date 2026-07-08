package info.zwyssig.platform.consensus;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bounded, fail-fast boot gate for E1c (#171). E1a mints the scoped {@code netctl-pki-<site>} token
 * out of band, so at the instant Ratis wants to start the local OpenBao may still answer HTTP 403 to
 * {@code issue()}/{@code getCaCertificatePem()}. This gate polls the attempt with a fixed interval until
 * it succeeds OR a hard deadline passes; on the deadline it THROWS. It NEVER returns a fallback value,
 * because the one failure mode E1c must prevent is silently starting the Ratis transport in plaintext.
 *
 * <p>The clock and the sleep are injected so the retry/timeout decision is unit-testable without wall
 * time. Production passes {@code System::currentTimeMillis} and a real {@link Thread#sleep} shim.
 */
public final class BoundedMaterialGate {

    private static final Logger log = LoggerFactory.getLogger(BoundedMaterialGate.class);

    private BoundedMaterialGate() {
    }

    /**
     * Poll {@code attempt} until it returns without throwing, or until {@code timeoutMillis} elapse.
     *
     * @param what           a short label for the material being awaited (for the log/exception message)
     * @param attempt        the provisioning-sensitive call (throws while the token is not yet present)
     * @param timeoutMillis  the hard upper bound on the total wait
     * @param pollMillis     the pause between failed attempts
     * @param clockMillis    a monotonic-enough millisecond clock (injected for testability)
     * @param sleepMillis    the pause primitive (injected for testability)
     * @return the first successful result of {@code attempt}
     * @throws IllegalStateException on timeout - deliberately, so the node crash-loops rather than
     *                               starting Ratis in plaintext
     */
    public static <T> T awaitFailFast(String what, Supplier<T> attempt, long timeoutMillis, long pollMillis,
                               LongSupplier clockMillis, LongConsumer sleepMillis) {
        long deadline = clockMillis.getAsLong() + timeoutMillis;
        RuntimeException last = null;
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                T value = attempt.get();
                if (attempts > 1) {
                    log.info("Ratis mTLS material '{}' became available after {} attempt(s)", what, attempts);
                }
                return value;
            } catch (RuntimeException e) {
                last = e;
                if (clockMillis.getAsLong() >= deadline) {
                    throw new IllegalStateException(
                            "Ratis mTLS material '" + what + "' not available within " + timeoutMillis
                                    + "ms; refusing to start Ratis in plaintext (never fall back)", last);
                }
                log.info("Ratis mTLS material '{}' not ready yet (attempt {}): {} - retrying in {}ms",
                        what, attempts, e.getMessage(), pollMillis);
                sleepMillis.accept(pollMillis);
            }
        }
    }
}
