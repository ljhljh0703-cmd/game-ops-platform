// 디스코드 봇 연결 — enabled=true 일 때만 뜬다. 이벤트를 DiscordInbound 로 바꿔 처리기에 넘기는 얇은 층
package com.ljh.gameops.channel.discord;

import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "gameops.discord", name = "enabled", havingValue = "true")
public class DiscordGateway extends ListenerAdapter {
    private final DiscordProperties props;
    private final DiscordReportHandler handler;
    private JDA jda;

    public DiscordGateway(DiscordProperties props, DiscordReportHandler handler) {
        this.props = props;
        this.handler = handler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.botToken() == null || props.botToken().isBlank()) {
            throw new IllegalStateException("gameops.discord.enabled=true 인데 bot-token 이 비어 있습니다");
        }
        jda = JDABuilder.createLight(props.botToken(),
                        EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .addEventListeners(this)
                .build();
        jda.updateCommands().addCommands(
                Commands.slash("report", "버그·불편 제보")
                        .addOption(OptionType.STRING, "content", "무슨 일이 있었나요", true)
                        .addOption(OptionType.STRING, "game", "게임 코드 (예: gemtower)", false)
        ).queue();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        Message m = e.getMessage();
        String attachments = m.getAttachments().stream().map(Message.Attachment::getUrl).collect(Collectors.joining("\n"));
        handler.onChannelMessage(new DiscordInbound("msg:" + m.getId(), e.getChannel().getId(), e.getAuthor().getId(),
                        e.getAuthor().isBot(), null, m.getContentRaw(), attachments.isEmpty() ? null : attachments))
                .ifPresent(reply -> m.reply(reply).queue());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (!"report".equals(e.getName())) return;
        e.deferReply(true).queue(); // 3초 안에 응답해야 하므로 먼저 「처리 중」을 보낸다
        OptionMapping game = e.getOption("game");
        OptionMapping content = e.getOption("content");
        String reply = handler.onSlashCommand(new DiscordInbound("cmd:" + e.getId(), e.getChannelId(), e.getUser().getId(),
                false, game == null ? null : game.getAsString(), content == null ? null : content.getAsString(), null));
        e.getHook().sendMessage(reply).queue();
    }

    @PreDestroy
    public void stop() {
        if (jda != null) jda.shutdown();
    }
}
