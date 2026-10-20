// API 명세 머리말 — 제목·설명·관리자 키 방식. 명세는 /v3/api-docs, 사람이 보는 화면은 /swagger-ui.html
package com.ljh.gameops.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    public static final String ADMIN_KEY = "adminKey";

    @Bean
    OpenAPI gameOpsOpenApi() {
        return new OpenAPI()
                .info(new Info().title("게임 운영 플랫폼 API").version("0.2.0")
                        .description("""
                                플레이어 제보를 받아 분류·묶기한 이슈를 조회하고, 사람(또는 AI 에이전트)이 상태와 자동 판단을 고친다.
                                바꾸는 요청은 모두 `X-Api-Key` 헤더가 필요하다. 키마다 이름이 있어 감사 기록에 누가 바꿨는지 남는다
                                (사람 = local-admin, AI 에이전트 = ai-agent).
                                이 서버는 내 컴퓨터 안(127.0.0.1)에서만 열리고, 인터넷에는 슬랙 버튼 주소 하나만 열린다."""))
                .servers(List.of(new Server().url("http://127.0.0.1:8080").description("작가 맥 Docker")))
                .components(new Components().addSecuritySchemes(ADMIN_KEY,
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Api-Key")
                                .description("관리자 키(.env 의 ADMIN_API_KEY 또는 AGENT_API_KEY)")));
    }
}
