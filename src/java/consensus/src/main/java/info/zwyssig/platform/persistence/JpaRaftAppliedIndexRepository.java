package info.zwyssig.platform.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The Raft applied (term,index) high-water mark in Postgres (task #189). The catalog projection
 * records it in the SAME transaction as each apply, so the persisted state and the applied index
 * advance atomically: a crash between them can no longer leave one ahead of the other. On restart
 * the state machine reads it back to skip already-committed entries instead of relying on replay
 * idempotency alone. Not profile-gated (the table is a Liquibase-created ops table present in every
 * profile); it is only ever invoked by the ratis-only state machine / projection, so in a non-ratis
 * run it is simply an unused bean.
 *
 * DELIBERATE EntityManager remainder (#2): this is NOT migrated to Spring Data. raft_applied_index is a
 * native-SQL ops table (changelog v19), not a JPA-mapped @Entity, so JpaRepository has nothing to bind to;
 * and record() is an upsert that MUST join the caller's apply transaction (no @Transactional) for the
 * #189 crash-consistency invariant, which a Spring Data save() (own tx + Hibernate flush ordering) would
 * obscure. Inventing an entity purely to satisfy the idiom would be net-negative churn. Stays native EM.
 */
@Component
public class JpaRaftAppliedIndexRepository {

    /** The persisted Raft applied (term,index) high-water mark for a group. */
    public record AppliedMark(long term, long index) { }

    @PersistenceContext
    private EntityManager em;

    /**
     * Upsert the applied (term,index) for {@code groupId}. Deliberately has NO {@code @Transactional}:
     * it MUST join the caller's (projection apply) transaction so the index commits atomically with
     * the projection write. A read-then-update-else-insert (rather than a vendor-specific upsert) so
     * it runs identically on Postgres and the H2 test database; the Raft apply is single-threaded per
     * state machine, so there is no write race to guard against.
     */
    public void record(String groupId, long term, long index) {
        int updated = em.createNativeQuery(
                "UPDATE raft_applied_index SET applied_term = :t, applied_index = :i, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE group_id = :g")
                .setParameter("t", term)
                .setParameter("i", index)
                .setParameter("g", groupId)
                .executeUpdate();
        if (updated == 0) {
            em.createNativeQuery(
                    "INSERT INTO raft_applied_index (group_id, applied_term, applied_index, updated_at) "
                            + "VALUES (:g, :t, :i, CURRENT_TIMESTAMP)")
                    .setParameter("g", groupId)
                    .setParameter("t", term)
                    .setParameter("i", index)
                    .executeUpdate();
        }
    }

    /** The persisted applied mark for the group, or empty if none was recorded yet (a fresh database,
     *  or the first boot after upgrading an existing lab - then the caller falls back to the snapshot). */
    @Transactional(readOnly = true)
    public Optional<AppliedMark> find(String groupId) {
        List<?> rows = em.createNativeQuery(
                "SELECT applied_term, applied_index FROM raft_applied_index WHERE group_id = :g")
                .setParameter("g", groupId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = (Object[]) rows.get(0);
        return Optional.of(new AppliedMark(((Number) r[0]).longValue(), ((Number) r[1]).longValue()));
    }
}
