// 슬랙이 켜져 있지 않으면 로그 전송기를 쓴다
package com.ljh.gameops.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotifyConfig {
    @Bean
    @ConditionalOnMissingBean(NotificationSender.class)
    NotificationSender loggingSender() {
        return new LoggingSender();
    }
}
