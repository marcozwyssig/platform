package info.zwyssig.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * The applied-index repository is an upsert over a native-SQL ops table: record() inserts on first write
 * and updates in place thereafter (single-threaded per state machine), and find() reads the current mark
 * back or empty when none was written. Boots H2 + the platform raft_applied_index changelog fragment.
 */
@DataJpaTest
@Import(JpaRaftAppliedIndexRepository.class)
class JpaRaftAppliedIndexRepositoryTest {

    @Autowired
    private JpaRaftAppliedIndexRepository repo;

    @Test
    @DisplayName("find returns empty before any mark is recorded for a group")
    void findEmptyBeforeFirstRecord() {
        // arrange + act
        var mark = repo.find("group-A");
        // assert
        assertThat(mark).isEmpty();
    }

    @Test
    @DisplayName("record inserts then updates in place so find returns the latest (term,index)")
    void recordUpsertsAndFindReturnsLatest() {
        // arrange: first write inserts
        repo.record("group-A", 1L, 10L);
        // act: second write for the same group updates in place
        repo.record("group-A", 2L, 25L);
        // assert
        var mark = repo.find("group-A");
        assertThat(mark).isPresent();
        assertThat(mark.get().term()).isEqualTo(2L);
        assertThat(mark.get().index()).isEqualTo(25L);
    }
}
