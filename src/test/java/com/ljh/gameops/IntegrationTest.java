// 실제 PostgreSQL(테스트 컨테이너) 위에서 도는 통합 테스트의 공통 설정
package com.ljh.gameops;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTest.FakeSenderConfig.class)
public abstract class IntegrationTest {

    @TestConfiguration
    static class FakeSenderConfig {
        @Bean @Primary FakeSender fakeSender() { return new FakeSender(); }
    }

    @Autowired protected FakeSender sender;

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected com.ljh.gameops.classify.ManagedRuleSource ruleSource;

    @BeforeEach
    void cleanTables() {
        sender.reset();
        jdbc.execute("TRUNCATE notification_outbox, audit_log, correction, issue_report, issue, report, classifier_rule_version, slack_message, dev_handoff, merged_change, feedback_digest RESTART IDENTITY CASCADE");
        ruleSource.invalidate();
    }
}
