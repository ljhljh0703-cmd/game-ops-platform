// 게임 운영 플랫폼 진입점 — 제보 받기·분류·이슈 관리·알림
package com.ljh.gameops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class GameOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(GameOpsApplication.class, args);
    }
}
