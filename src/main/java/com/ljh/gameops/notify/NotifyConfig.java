// 슬랙이 꺼져 있으면 로그 전송기를 쓴다
package com.ljh.gameops.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotifyConfig {
    // 「다른 전송기가 없으면」 조건은 설정 클래스 읽는 순서에 따라 결과가 바뀐다(실제로 슬랙을 켰을 때 둘 다 떠서 서버가 안 켜졌다).
    // 슬랙 설정값 하나로 둘 중 하나만 뜨게 한다.
    @Bean
    @ConditionalOnProperty(prefix = "gameops.slack", name = "enabled", havingValue = "false", matchIfMissing = true)
    NotificationSender loggingSender() {
        return new LoggingSender();
    }
}
