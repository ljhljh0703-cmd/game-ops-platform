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

    /** 끊어 쓴 글 합치기(결정 44). 다 모이면 처리기로 넘기고 처음 글에 답한다 */
    private final DiscordMessageBuffer buffer;
    /** 답할 메시지 — 버퍼가 넘길 때 처음 글을 찾는다 */
    private final java.util.Map<String, Message> firstMessages = new java.util.concurrent.ConcurrentHashMap<>();

    public DiscordGateway(DiscordProperties props, DiscordReportHandler handler, GameCatalog games,
                          @org.springframework.beans.factory.annotation.Value("${gameops.discord.merge-quiet-ms:20000}") long quietMs,
                          @org.springframework.beans.factory.annotation.Value("${gameops.discord.merge-max-ms:60000}") long maxMs) {
        this.props = props;
        this.handler = handler;
        this.games = games;
        this.buffer = new DiscordMessageBuffer(quietMs, maxMs, in -> workers.submit(() -> {
            Message first = firstMessages.remove(in.externalId());
            handler.onChannelMessageReply(in).ifPresent(reply -> {
                if (first != null) first.replyEmbeds(DiscordCards.intake(reply, true)).queue();
            });
        }));
    }



    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.botToken() == null || props.botToken().isBlank()) {
            throw new IllegalStateException("gameops.discord.enabled=true 인데 bot-token 이 비어 있습니다");
        }
        // 봇 글이 @everyone·@here·역할을 부르지 못하게(플레이어 글·AI 글이 섞여도). 답장 대상 한 사람만 부른다(결정 43)
        net.dv8tion.jda.api.utils.messages.MessageRequest.setDefaultMentions(java.util.EnumSet.noneOf(Message.MentionType.class));
        jda = JDABuilder.createLight(props.botToken(),
                        EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .addEventListeners(this)
                .build();
        jda.updateCommands().addCommands(
                Commands.slash("report", "버그·불편 제보")
                        .addOption(OptionType.STRING, "content", "무슨 일이 있었나요", true)
                        .addOptions(gameOption())
                        .addOption(OptionType.STRING, "version", "게임 버전 (예: 1.2.3)", false)
                        .addOption(OptionType.STRING, "device", "기기 · 브라우저 (예: 아이폰 15, 크롬)", false)
                        .addOption(OptionType.STRING, "steps", "어떻게 하면 다시 생기나요 (예: 3웨이브에서 일시정지 후 재개)", false)
                        .addOption(OptionType.ATTACHMENT, "screenshot", "스크린샷", false)
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
        // 답장할 곳은 실제 채널(스레드면 스레드) + 메시지. 해결되면 이 글에 「고쳤어요」를 단다
        String replyTo = e.getChannel().getId() + ":" + m.getId();
        DiscordInbound in = new DiscordInbound("msg:" + m.getId(), ch[0], ch[1], e.getAuthor().getId(),
                e.getAuthor().isBot(), null, m.getContentRaw(), attachments.isEmpty() ? null : attachments, replyTo, null,
                roles(e.getMember()));
        String original = originalOf(m, e.getJDA().getSelfUser().getId());
        if (original != null) {
            workers.submit(() -> handler.onReplyMessage(in, original)
                    .ifPresent(reply -> m.replyEmbeds(DiscordCards.intake(reply, true)).queue()));
            return;
        }
        if (in.fromBot() || !handler.isReportChannel(in)) return;
        // 이어 쓴 줄은 모아서 제보 하나로. 답장은 처음 글에
        firstMessages.putIfAbsent(in.externalId(), m);
        buffer.add(in);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (!"report".equals(e.getName())) return;
        e.deferReply(true).queue(); // 3초 안에 응답해야 하므로 먼저 「처리 중」을 보낸다
        OptionMapping game = e.getOption("game");
        OptionMapping content = e.getOption("content");
        String[] ch = slashChannel(e);
        OptionMapping shot = e.getOption("screenshot");
        DiscordInbound in = new DiscordInbound("cmd:" + e.getId(), ch[0], ch[1], e.getUser().getId(),
                false, game == null ? null : game.getAsString(), content == null ? null : content.getAsString(),
                shot == null ? null : shot.getAsAttachment().getUrl(), null,
                com.ljh.gameops.report.ReproInfo.of(str(e, "version"), str(e, "device"), str(e, "steps")), roles(e.getMember()));
        workers.submit(() -> e.getHook().sendMessageEmbeds(DiscordCards.intake(handler.onSlashCommandReply(in), false)).queue());
    }

    /** 역할 ID 와 이름 둘 다(설정에 어느 쪽을 적어도 되게) */
    static java.util.List<String> roles(net.dv8tion.jda.api.entities.Member member) {
        if (member == null) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var r : member.getRoles()) { out.add(r.getId()); out.add(r.getName()); }
        return out;
    }

    /**
     * 답글이면 플레이어 원래 글의 메시지 ID. 봇의 접수 답장에 단 답글이면 그 답장이 가리키는 글로 거슬러 올라간다.
     * 답글이 아니면 null
     */
    static String originalOf(Message m, String selfId) {
        var ref = m.getMessageReference();
        if (ref == null) return null;
        Message target = m.getReferencedMessage();
        if (target != null && target.getAuthor() != null && selfId.equals(target.getAuthor().getId())
                && target.getMessageReference() != null) {
            return target.getMessageReference().getMessageId();
        }
        return ref.getMessageId();
    }

    private static String str(SlashCommandInteractionEvent e, String name) {
        OptionMapping o = e.getOption(name);
        return o == null ? null : o.getAsString();
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

    /** 해결 답장 등 다른 곳에서 봇으로 보낼 때. 아직 안 켜졌으면 null */
    public JDA jda() { return jda; }

    @PreDestroy
    public void stop() {
        buffer.flushAll(); // 모아 둔 글을 먼저 넘기고(잃지 않게) 일꾼이 끝낼 때까지 잠깐 기다린다
        workers.shutdown();
        try { workers.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (jda != null) jda.shutdown();
    }
}
