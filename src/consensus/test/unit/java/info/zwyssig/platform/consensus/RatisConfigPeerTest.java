package info.zwyssig.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.util.NetUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #772: netctl's v6 Raft peers arrive as id:[fd00:c71:1::c7]:9810. RatisConfig.peer must yield a RaftPeer
 * whose id is the label and whose address resolves to the right port via NetUtils.createSocketAddr, for
 * BOTH the bracketed v6 form and the legacy v4 host:port form.
 */
class RatisConfigPeerTest {

    @Test
    @DisplayName("bracketed v6 peer yields the id and a port-9810 socket address")
    void bracketedV6PeerResolvesPort() {
        // arrange
        String spec = "zh:[fd00:c71:1::c7]:9810";

        // act
        RaftPeer peer = RatisConfig.peer(spec);

        // assert
        assertEquals("zh", peer.getId().toString());
        assertEquals(9810, NetUtils.createSocketAddr(peer.getAddress()).getPort());
    }

    @Test
    @DisplayName("legacy v4 host:port peer still yields the id and its port")
    void legacyV4PeerResolvesPort() {
        // arrange
        String spec = "be:10.32.2.2:9810";

        // act
        RaftPeer peer = RatisConfig.peer(spec);

        // assert
        assertEquals("be", peer.getId().toString());
        assertEquals(9810, NetUtils.createSocketAddr(peer.getAddress()).getPort());
    }
}
