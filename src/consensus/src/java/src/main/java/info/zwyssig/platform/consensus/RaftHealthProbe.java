package info.zwyssig.platform.consensus;

import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.RaftServer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Reads this node's Raft quorum status from the running RaftServer, so a health probe can tell
 * "ready" (in a functioning quorum) from merely "process up". It keeps the ratis types contained in
 * the infrastructure module and exposes a plain {@link Status} record, which the web module's
 * actuator HealthIndicator maps to UP/DOWN without depending on ratis. Active only under "ratis".
 *
 * Healthy means a leader is known for the group (this node is the leader, or a follower that has a
 * leader) - i.e. the node can make progress on the replicated log. No known leader (election in
 * progress, lost quorum) or any probe failure is reported as not-healthy, which is the conservative,
 * fail-safe answer: a node that cannot confirm quorum should not be advertised as ready.
 */
@Component
@Profile("ratis")
public class RaftHealthProbe {

    /** Plain (ratis-free) quorum status for the health layer above. */
    public record Status(boolean healthy, String detail) { }

    private final RaftServer server;
    private final RaftGroup group;

    public RaftHealthProbe(RaftServer server, RaftGroup group) {
        this.server = server;
        this.group = group;
    }

    public Status status() {
        try {
            RaftServer.Division division = server.getDivision(group.getGroupId());
            if (division == null) {
                return new Status(false, "no raft division for group " + group.getGroupId());
            }
            DivisionInfo info = division.getInfo();
            RaftPeerId leader = info.getLeaderId();
            String role = String.valueOf(info.getCurrentRole());
            if (leader == null) {
                return new Status(false, "no leader yet (role=" + role + ", quorum not formed)");
            }
            return new Status(true, "leader=" + leader + " role=" + role);
        } catch (Exception e) {
            return new Status(false, "raft probe failed: " + e.getMessage());
        }
    }
}
