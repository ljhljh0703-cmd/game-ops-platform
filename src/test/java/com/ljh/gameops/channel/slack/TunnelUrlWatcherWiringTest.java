// 터널 주소를 켠 설정으로 서버가 실제로 뜨는지 — 지킴이는 이 설정이 있을 때만 만들어져서 다른 테스트로는 못 잡는다
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "gameops.slack.tunnel-metrics-url=http://127.0.0.1:1/quicktunnel",
        "gameops.slack.tunnel-check-ms=3600000"
})
class TunnelUrlWatcherWiringTest extends IntegrationTest {
    @Autowired TunnelUrlWatcher watcher;

    @Test
    void 터널_주소를_켜도_서버가_뜬다() {
        assertThat(watcher).isNotNull();
        watcher.check(); // 터널이 없으면 조용히 넘긴다
    }
}
