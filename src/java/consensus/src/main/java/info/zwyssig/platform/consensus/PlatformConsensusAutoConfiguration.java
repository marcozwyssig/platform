package info.zwyssig.platform.consensus;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers the shared consensus machine's beans in a consuming product's Spring context WITHOUT the
 * product having to component-scan info.zwyssig.platform. The product's @SpringBootApplication picks this
 * up via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports. Beans keep
 * their own @Profile/@ConditionalOnProperty gates: RatisConfig + RaftHealthProbe are @Profile("ratis"),
 * while the applied-index repository is registered in every profile (unused under !ratis), exactly as its
 * @Component scan did before the move. The product supplies the neutral StateMachine + GrpcTlsConfig beans
 * the RatisConfig mechanism injects.
 */
@AutoConfiguration
@Import({ RatisConfig.class, RaftHealthProbe.class, info.zwyssig.platform.persistence.JpaRaftAppliedIndexRepository.class })
public class PlatformConsensusAutoConfiguration {
}
