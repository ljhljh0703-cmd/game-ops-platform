// 시각을 주입받게 해 테스트에서 시간을 고정할 수 있게 한다
package com.ljh.gameops.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() { return Clock.systemDefaultZone(); }
}
