// 패치노트를 디스코드 채널에 올린다(결정 39). 봇이 아직 안 붙었으면 예외 → 대기열이 다시 보낸다
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.notify.Announcer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "gameops.discord", name = "enabled", havingValue = "true")
public class DiscordAnnouncer implements Announcer {
    static final int BLUE = 0x5865F2;
    private final DiscordGateway gateway;

    public DiscordAnnouncer(DiscordGateway gateway) { this.gateway = gateway; }

    @Override
    public String announce(String channelId, String title, String text) {
        JDA jda = gateway.jda();
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) throw new IllegalStateException("디스코드 봇이 아직 연결되지 않았어요");
        MessageChannel ch = jda.getChannelById(MessageChannel.class, channelId);
        if (ch == null) throw new IllegalStateException("디스코드 채널을 못 찾았어요: " + channelId);
        var embed = new EmbedBuilder().setColor(BLUE).setTitle(DiscordCards.cut(title, 250))
                .setDescription(text.length() > 4000 ? text.substring(0, 3999) + "…" : text).setFooter(DiscordCards.FOOTER).build();
        // 글 안의 @everyone·역할 멘션이 울리지 않게 멘션을 모두 끈다
        var msg = MessageCreateData.fromEmbeds(embed);
        try {
            return ch.sendMessage(msg).setAllowedMentions(EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class))
                    .submit().get(10, TimeUnit.SECONDS).getId();
        } catch (Exception e) {
            throw new IllegalStateException("디스코드 공지 실패: " + e.getMessage(), e);
        }
    }
}
