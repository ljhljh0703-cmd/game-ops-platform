// 스레드·포럼 글은 부모 채널로 게임을 정한다
package com.ljh.gameops.channel.discord;

import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BaseChannelTest {
    @Test
    void 일반_채널은_그대로() {
        TextChannel c = mock(TextChannel.class);
        when(c.getId()).thenReturn("10");
        when(c.getName()).thenReturn("무브퓨-버그제보");
        assertThat(DiscordGateway.baseChannel(c)).containsExactly("10", "무브퓨-버그제보");
    }

    @Test
    void 스레드는_부모_채널() {
        IThreadContainerUnion parent = mock(IThreadContainerUnion.class);
        when(parent.getId()).thenReturn("10");
        when(parent.getName()).thenReturn("무브퓨-버그제보");
        ThreadChannel t = mock(ThreadChannel.class);
        when(t.getId()).thenReturn("99");
        when(t.getName()).thenReturn("점프 버그 모음");
        when(t.getParentChannel()).thenReturn(parent);
        assertThat(DiscordGateway.baseChannel(t)).containsExactly("10", "무브퓨-버그제보");
    }

    @Test
    void 부모를_못_얻으면_스레드_ID만() {
        ThreadChannel t = mock(ThreadChannel.class);
        when(t.getParentChannel()).thenThrow(new IllegalStateException("not cached"));
        when(t.getId()).thenReturn("99");
        assertThat(DiscordGateway.baseChannel(t)).containsExactly("99", null);
    }
}
