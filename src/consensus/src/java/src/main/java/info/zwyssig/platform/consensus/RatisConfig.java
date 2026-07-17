package info.zwyssig.platform.consensus;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.TimeDuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Wires the Ratis MECHANISM (RaftGroup / RaftServer / RaftClient / lifecycle), the shared half of the
 * consensus transport. Active only under the "ratis" profile. The product-coupled beans stay product-side
 * and are injected here by their NEUTRAL Ratis types: the replicated {@link StateMachine} and the mTLS
 * {@link GrpcTlsConfig} (via {@link ObjectProvider}, absent -> plaintext). Group/peers/storage are
 * deployment config carried by {@link RatisProperties}.
 *
 * NOTE: verify against your Ratis version. This is the standard server+client
 * build shape (gRPC transport from ratis-grpc).
 */
@Configuration
@Profile("ratis")
public class RatisConfig {


    private static RaftPeer peer(String spec) {
        // spec = "id:host:port"
        String[] p = spec.split(":", 2);
        return RaftPeer.newBuilder().setId(p[0]).setAddress(p[1]).build();
    }

    @Bean
    public RaftGroup raftGroup(RatisProperties props) {
        List<RaftPeer> peers = props.peers().stream().map(RatisConfig::peer).toList();
        return RaftGroup.valueOf(RaftGroupId.valueOf(UUID.fromString(props.groupId())), peers);
    }

    @Bean
    public RaftServer raftServer(RatisProperties props, RaftGroup group,
                                 StateMachine stateMachine,
                                 ObjectProvider<GrpcTlsConfig> tlsConfig) throws Exception {
        RaftPeer self = group.getPeers().stream()
                .filter(p -> p.getId().toString().equals(props.selfId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("selfId not in peers: " + props.selfId()));

        var raft = new RaftProperties();
        RaftConfigKeysHelper.setGrpc(raft);
        var storageDir = new File(props.storageDir());
        RaftServerConfigKeys.setStorageDir(raft, List.of(storageDir));
        GrpcConfigKeys.Server.setPort(raft, NetUtils.createSocketAddr(self.getAddress()).getPort());

        // Election timeout. The Ratis default (150-300ms) is far too tight for this
        // lab on macOS (containerlab inside Colima, IOL under Rosetta, ~20 containers):
        // heartbeats miss the window under load and the cluster thrashes through
        // leader elections, losing writes committed during the storm. Widen it so a
        // momentarily slow follower does not trigger a re-election. 1-2s still thrashed
        // once the AUTO-deploy reconciler added per-sweep write load (observed a 5-min
        // no-leader gap across 5 terms during a 17-min integration run, failing every
        // write that landed in the gap); 3-6s holds. Failover stays in seconds, which is
        // right for a few-site control plane (this is not a sub-second-failover system).
        RaftServerConfigKeys.Rpc.setTimeoutMin(raft, TimeDuration.valueOf(3, TimeUnit.SECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(raft, TimeDuration.valueOf(6, TimeUnit.SECONDS));
        RaftServerConfigKeys.Rpc.setRequestTimeout(raft, TimeDuration.valueOf(10, TimeUnit.SECONDS));

        // Ratis defaults to FORMAT, which aborts if the storage dir already exists
        // (after a restart or an earlier crashed boot). Format only on first boot;
        // otherwise recover the existing store.
        String[] existing = storageDir.list();
        RaftStorage.StartupOption startup =
                (storageDir.isDirectory() && existing != null && existing.length > 0)
                        ? RaftStorage.StartupOption.RECOVER
                        : RaftStorage.StartupOption.FORMAT;

        // Build only. start() triggers the state machine, which reseeds the VNI
        // counter from the catalog tables, so it must run after Liquibase has migrated.
        // We start it from a SmartLifecycle bean, which runs after the context is
        // fully initialized (i.e. after Liquibase), instead of here in the factory.
        RaftServer.Builder builder = RaftServer.newBuilder()
                .setServerId(self.getId())
                .setGroup(group)
                .setProperties(raft)
                .setStateMachine(stateMachine)
                .setOption(startup);

        // #171 E1c: mutual TLS on the inbound (Server) and management (Admin) gRPC endpoints, only when
        // a GrpcTlsConfig bean is present (the product exposes it when its tls flag is enabled). When
        // absent, setParameters is never called and the server binds plaintext exactly as before.
        GrpcTlsConfig tlsConf = tlsConfig.getIfAvailable();
        if (tlsConf != null) {
            Parameters serverParams = new Parameters();
            GrpcConfigKeys.Server.setTlsConf(serverParams, tlsConf);
            GrpcConfigKeys.Admin.setTlsConf(serverParams, tlsConf);
            builder.setParameters(serverParams);
        }
        return builder.build();
    }

    /**
     * Starts and stops the RaftServer on the context lifecycle. SmartLifecycle.start()
     * runs at the end of the refresh, after every singleton (Liquibase included) is ready,
     * which is what keeps reseed() from hitting an unmigrated schema.
     */
    @Bean
    public SmartLifecycle raftServerLifecycle(RaftServer server) {
        return new SmartLifecycle() {
            private volatile boolean running = false;

            @Override public void start() {
                try {
                    server.start();
                    running = true;
                } catch (Exception e) {
                    throw new IllegalStateException("failed to start RaftServer", e);
                }
            }

            @Override public void stop() {
                try {
                    server.close();
                } catch (Exception e) {
                    // best effort on shutdown
                } finally {
                    running = false;
                }
            }

            @Override public boolean isRunning() {
                return running;
            }
        };
    }

    @Bean
    public RaftClient raftClient(RaftGroup group, ObjectProvider<GrpcTlsConfig> tlsConfig) {
        var raft = new RaftProperties();
        RaftConfigKeysHelper.setGrpc(raft);
        RaftClient.Builder builder = RaftClient.newBuilder()
                .setProperties(raft)
                .setRaftGroup(group);

        // #171 E1c: present the SAME node leaf as the Raft client (outbound to peers), matching the
        // server side. Gated on the GrpcTlsConfig bean; absent -> plaintext client as before.
        GrpcTlsConfig tlsConf = tlsConfig.getIfAvailable();
        if (tlsConf != null) {
            Parameters clientParams = new Parameters();
            GrpcConfigKeys.Client.setTlsConf(clientParams, tlsConf);
            builder.setParameters(clientParams);
        }
        return builder.build();
    }

    /** Small helper to keep the RpcType setting in one place. */
    static final class RaftConfigKeysHelper {
        static void setGrpc(RaftProperties props) {
            org.apache.ratis.RaftConfigKeys.Rpc.setType(props, SupportedRpcType.GRPC);
        }
    }
}
