plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.7")
    }
}

dependencies {
    // Spring wiring only (no Vaadin, no web): @Configuration/@Bean/@Component/@Profile + auto-config + tx.
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-context")
    api("org.springframework:spring-tx")
    api("jakarta.persistence:jakarta.persistence-api")
    api("org.slf4j:slf4j-api")

    // consensus: Apache Ratis (Raft) - same versions as netctl :infrastructure.
    api("org.apache.ratis:ratis-server:3.1.3")
    api("org.apache.ratis:ratis-client:3.1.3")
    api("org.apache.ratis:ratis-grpc:3.1.3")
    api("org.apache.ratis:ratis-common:3.1.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-jdbc-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-liquibase")
    runtimeOnly("org.postgresql:postgresql")
    // Gradle 9 no longer auto-provides the JUnit Platform launcher on the test runtime classpath, and
    // this standalone module has no transitive that pulls it (netctl :infrastructure gets it via
    // allure-junit5). Declare it explicitly; version managed by the spring-boot-dependencies BOM (JUnit BOM).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xshare:off")
}

sourceSets {
    test {
        java.setSrcDirs(listOf(file("../../../test/java/consensus")))
        resources.setSrcDirs(listOf(file("../../../test/java/consensus/resources")))
    }
}
