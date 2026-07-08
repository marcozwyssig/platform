/** Shared persistence mechanism: the Raft applied-index ops table (crash-consistency high-water mark),
 *  joined to the caller's apply transaction. Not a JPA @Entity; native SQL over the raft_applied_index
 *  table the platform changelog fragment creates. */
package info.zwyssig.platform.persistence;
