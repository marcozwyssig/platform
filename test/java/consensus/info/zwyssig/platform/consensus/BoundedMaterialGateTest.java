package info.zwyssig.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bounded, fail-fast boot gate for E1c: it waits for the local OpenBao to hand out TLS material
 * (leaf + root), tolerating the ~2 min provisioning window, but on timeout it FAILS rather than falling
 * back to plaintext. Silent plaintext fallback is the exact failure mode E1c must prevent, so the gate
 * MUST throw (never return a value) once the deadline passes.
 */
class BoundedMaterialGateTest {

    @Test
    @DisplayName("awaitFailFast returns the value once the attempt succeeds after transient failures")
    void returnsValueAfterTransientFailures() {
        // arrange: the first two attempts 403 (no token yet), the third succeeds; a virtual clock
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong(0);
        AtomicInteger sleeps = new AtomicInteger();

        // act: 10s timeout, 1s poll, virtual clock advanced by the injected sleep
        String result = BoundedMaterialGate.awaitFailFast(
                "leaf",
                () -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new IllegalStateException("OpenBao PKI HTTP 403");
                    }
                    return "material";
                },
                10_000, 1_000,
                now::get,
                ms -> {
                    sleeps.incrementAndGet();
                    now.addAndGet(ms);
                });

        // assert
        assertEquals("material", result, "the gate returns the material once provisioning completes");
        assertEquals(3, calls.get(), "it retried until the attempt succeeded");
        assertEquals(2, sleeps.get(), "it slept between the two failed attempts");
    }

    @Test
    @DisplayName("awaitFailFast throws (never falls back) once the deadline passes")
    void throwsOnTimeoutNeverFallsBack() {
        // arrange: the attempt always fails (provisioning never completes); a virtual clock
        AtomicLong now = new AtomicLong(0);
        AtomicInteger calls = new AtomicInteger();

        // act + assert: bounded wait, then a hard failure
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BoundedMaterialGate.awaitFailFast(
                        "leaf",
                        () -> {
                            calls.incrementAndGet();
                            throw new IllegalStateException("OpenBao PKI HTTP 403");
                        },
                        5_000, 1_000,
                        now::get,
                        now::addAndGet));

        // assert: it fails fast with a plaintext-refusing message, and it did try at least once
        assertTrue(ex.getMessage().toLowerCase().contains("plaintext"),
                "the failure must state it refuses to start Ratis in plaintext");
        assertTrue(calls.get() >= 1, "the gate attempted at least once before giving up");
        assertFalse(now.get() > 60_000, "the wait stayed bounded (well under a minute for a 5s budget)");
    }
}
