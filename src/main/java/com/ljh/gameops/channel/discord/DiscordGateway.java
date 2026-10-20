// 디스코드 봇 연결 — enabled=true 일 때만 뜬다. 이벤트를 DiscordInbound 로 바꿔 처리기에 넘기는 얇은 층
package com.ljh.gameops.channel.discord;

import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import com.ljh.gameops.game.GameCatalog;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "gameops.discord", name = "enabled", havingValue = "true")
public class DiscordGateway extends ListenerAdapter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DiscordGateway.class);
    private final DiscordProperties props;
    private final DiscordReportHandler handler;
    private final GameCatalog games;
    private JDA jda;
    // 디스코드 라이브러리의 이벤트 스레드는 하나다. 분류(LLM 호출)를 거기서 하면 뒤 명령이 3초 제한을 넘기므로 따로 돌린다.
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    public DiscordGateway(DiscordProperties props, DiscordReportHandler handler, GameCatalog games) {
        this.props = props;
        this.handler = handler;
        this.games = games;
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
                        .addOptions(gameOption())
        ).queue();
    }

    /** 게임을 목록에서 고르게 한다(디스코드 선택지는 25개까지). 안 고르면 쓴 채널의 게임. */
    private OptionData gameOption() {
        OptionData o = new OptionData(OptionType.STRING, "game", "어느 게임인가요 (안 고르면 이 채널의 게임)", false);
        if (games.all().size() > 25) log.warn("게임이 {}개라 /report 선택지에는 앞 25개만 보입니다(나머지는 채널로 구분)", games.all().size());
        games.all().stream().limit(25).forEach(g -> o.addChoice(g.name(), g.code()));
        return o;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        Message m = e.getMessage();
        String attachments = m.getAttachments().stream().map(Message.Attachment::getUrl).collect(Collectors.joining("\n"));
        String[] ch = baseChannel(e.getChannel());
        workers.submit(() -> handler.onChannelMessage(new DiscordInbound("msg:" + m.getId(), ch[0], ch[1], e.getAuthor().getId(),
                        e.getAuthor().isBot(), null, m.getContentRaw(), attachments.isEmpty() ? null : attachments))
                .ifPresent(reply -> m.reply(reply).queue()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (!"report".equals(e.getName())) return;
        e.deferReply(true).queue(); // 3초 안에 응답해야 하므로 먼저 「처리 중」을 보낸다
        OptionMapping game = e.getOption("game");
        OptionMapping content = e.getOption("content");
        String[] ch = slashChannel(e);
        DiscordInbound in = new DiscordInbound("cmd:" + e.getId(), ch[0], ch[1], e.getUser().getId(),
                false, game == null ? null : game.getAsString(), content == null ? null : content.getAsString(), null);
        workers.submit(() -> e.getHook().sendMessage(handler.onSlashCommand(in)).queue());
    }

    /**
     * 게임을 정할 채널의 [ID, 이름]. 스레드·포럼 글이면 스레드가 아니라 그 부모 채널로 본다
     * (게임 채널 안 스레드에 쓴 제보가 무시되거나 기본 게임으로 잘못 들어가지 않게).
     */
    static String[] baseChannel(Channel c) {
        if (c instanceof ThreadChannel t) {
            try {
                var parent = t.getParentChannel();
                return new String[]{parent.getId(), parent.getName()};
            } catch (RuntimeException ex) {
                return new String[]{t.getId(), null}; // 부모를 모르면 스레드 자체(게임 채널로 안 잡혀 무시됨)
            }
        }
        return new String[]{c.getId(), c.getName()};
    }

    // 명령을 쓴 채널 — 캐시가 없어 못 얻으면 ID 만(그때는 채널 ID 로만 게임을 찾는다)
    private static String[] slashChannel(SlashCommandInteractionEvent e) {
        try {
            return baseChannel(e.getChannel());
        } catch (RuntimeException ex) {
            return new String[]{e.getChannelId(), null};
        }
    }

    @PreDestroy
    public void stop() {
        workers.shutdown();
        if (jda != null) jda.shutdown();
    }
}
