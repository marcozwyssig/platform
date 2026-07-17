package info.zwyssig.platform.consensus;

import java.util.List;

/**
 * Ratis cluster config. peers is the voting set (the small stable core, 3 or 5
 * sites), each as "id:host:port". selfId must be one of them. Learners that only
 * read the catalog are configured separately and are not voters.
 *
 * <p>Neutral platform type: a plain record, NOT annotated {@code @ConfigurationProperties}. A consuming
 * product binds its own config key onto it (netctl maps {@code netctl.consensus.ratis} via a mapping
 * bean; infractl may bind {@code platform.consensus.ratis} directly). The netctl-flavoured group/storage
 * defaults ride along for now and never fire in netctl (the mapping bean always supplies real values).
 */
public record RatisProperties(
        String groupId,
        String selfId,
        List<String> peers,
        String storageDir,
        // Local read-model apply-wait budget: a committed write applies asynchronously on a
        // follower, so a read-back polls until it lands. attempts x millis = total budget; the
        // defaults give 100 x 20ms = 2s. Configurable so a slow/loaded site can widen it.
        int applyWaitAttempts,
        long applyWaitMillis,
        // #171 E1c: mutual TLS on the inter-site channel, config-gated (default off).
        Tls tls) {

    public RatisProperties {
        if (groupId == null || groupId.isBlank()) {
            groupId = "02511d47-d67c-49a3-9011-abb3109a44c1"; // fixed federation group UUID
        }
        if (storageDir == null || storageDir.isBlank()) {
            storageDir = "/var/lib/netctl/ratis";
        }
        if (peers == null) {
            peers = List.of();
        }
        if (applyWaitAttempts <= 0) {
            applyWaitAttempts = 100;
        }
        if (applyWaitMillis <= 0) {
            applyWaitMillis = 20;
        }
        if (tls == null) {
            tls = new Tls(null, null, null);
        }
    }

    /**
     * Ratis inter-site mutual-TLS config (#171 E1c). {@code enabled} is a DEDICATED sub-flag, separate
     * from {@code netctl.openbao.enabled}, so the rollout is staged: converge the E1b truststore first
     * (OpenBao on, transport still plaintext, cluster healthy), then flip this flag in one coordinated
     * redeploy. Enabling it REQUIRES {@code netctl.openbao.enabled=true}.
     *
     * <p><b>All-or-nothing, cluster-wide.</b> Raft cannot mix plaintext and TLS peers: a TLS server
     * rejects a plaintext client and vice versa, so a half-flipped cluster splits and never forms a
     * quorum. This flag MUST be uniform across every voter; there is no supported rolling upgrade and no
     * mixed state. Flip it on all voters together in one redeploy.
     *
     * <p>{@code waitTimeoutSeconds}/{@code waitPollMillis} bound the fail-fast boot gate that waits for
     * the local OpenBao to provision the scoped token before Ratis binds (never plaintext fallback).
     */
    public record Tls(Boolean enabled, Integer waitTimeoutSeconds, Long waitPollMillis) {
        public Tls {
            if (enabled == null) {
                enabled = Boolean.FALSE;
            }
            if (waitTimeoutSeconds == null || waitTimeoutSeconds <= 0) {
                waitTimeoutSeconds = 180; // E1a mints the scoped token ~2 min after controller boot
            }
            if (waitPollMillis == null || waitPollMillis <= 0) {
                waitPollMillis = 3000L;
            }
        }
    }
}
