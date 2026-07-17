package info.zwyssig.platform;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot configuration for the platform persistence-slice tests (@DataJpaTest). The
 * platform consensus module is a library, not a Spring Boot application, so @DataJpaTest needs a
 * configuration root to bootstrap the JPA context against in-memory H2 (see application.properties in
 * the test resources). It lives in the platform root package so the @DataJpaTest config search finds it
 * from every sub-package (persistence, consensus). Mirrors netctl's PersistenceTestConfig.
 */
@SpringBootApplication
class PlatformTestConfig {
}
