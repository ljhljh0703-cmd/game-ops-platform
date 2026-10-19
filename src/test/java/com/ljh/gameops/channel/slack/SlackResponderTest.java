// 슬랙 답장 주소 — 슬랙 주소가 아니면 부르지 않는다
package com.ljh.gameops.channel.slack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackResponderTest {
    @Test
    void 슬랙_주소만_허용() {
        assertThat(SlackResponder.isSlackUrl("https://hooks.slack.com/actions/T1/123/abc")).isTrue();
        assertThat(SlackResponder.isSlackUrl("http://hooks.slack.com/actions/x")).isFalse();
        assertThat(SlackResponder.isSlackUrl("https://hooks.slack.com.evil.example/x")).isFalse();
        assertThat(SlackResponder.isSlackUrl("https://169.254.169.254/latest")).isFalse();
        assertThat(SlackResponder.isSlackUrl(null)).isFalse();
    }
}
