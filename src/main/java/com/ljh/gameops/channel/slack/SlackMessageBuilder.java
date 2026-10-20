// 슬랙 메시지 모양 — 채널 고르기와 상태 버튼 만들기(외부 호출 없음, 테스트 대상).
// 채널: 게임별 채널(games.json)이 먼저, 없으면 공용 채널(.env). 심각하면 게임 긴급 → 공용 긴급 → 보통 채널 순.
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.RenderedNotification;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.ButtonElement;

import java.util.ArrayList;
import java.util.List;

public class SlackMessageBuilder {
    public static final String ACTION_PREFIX = "issue_status_";
    private final SlackProperties props;
    private final GameCatalog games;

    public SlackMessageBuilder(SlackProperties props) { this(props, GameCatalog.empty()); }

    public SlackMessageBuilder(SlackProperties props, GameCatalog games) {
        this.props = props;
        this.games = games;
    }

    public String channelFor(RenderedNotification n) {
        var game = games.byCode(n.gameCode());
        String normal = firstNonBlank(game.map(GameSpec::slackChannel).orElse(null),
                props.channels() == null ? null : props.channels().defaultChannel());
        boolean high = n.severity() == Severity.HIGH || n.severity() == Severity.CRITICAL;
        if (high) {
            String urgent = firstNonBlank(game.map(GameSpec::slackUrgentChannel).orElse(null),
                    props.channels() == null ? null : props.channels().urgent());
            if (urgent != null) return urgent;
        }
        if (normal == null) throw new IllegalStateException("슬랙 채널이 설정되지 않았습니다(게임: " + n.gameCode() + ")");
        return normal;
    }

    /** 카드에 보일 봇 이름·아이콘 [이름, 아이콘 주소]. 꺼져 있으면 null(슬랙 앱 기본 이름·아이콘) */
    public String[] identityFor(RenderedNotification n) {
        var id = props.identity();
        if (id == null || !id.enabled()) return null;
        var game = games.byCode(n.gameCode());
        String name = firstNonBlank(game.map(GameSpec::botName).orElse(null), id.name());
        String icon = firstNonBlank(game.map(GameSpec::icon).orElse(null), id.icon());
        if (name == null && icon == null) return null;
        return new String[]{name, icon};
    }

    /** 게임 채널로 못 보냈을 때(채널 없음·봇이 못 들어감) 대신 보낼 공용 채널. 게임 채널과 같거나 없으면 null. */
    public String sharedFallbackFor(RenderedNotification n, String failedChannel) {
        boolean high = n.severity() == Severity.HIGH || n.severity() == Severity.CRITICAL;
        String shared = firstNonBlank(high && props.channels() != null ? props.channels().urgent() : null,
                props.channels() == null ? null : props.channels().defaultChannel());
        return shared == null || shared.equals(failedChannel) ? null : shared;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    public List<LayoutBlock> blocks(RenderedNotification n) {
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(SectionBlock.builder().text(MarkdownTextObject.builder().text(n.text()).build()).build());
        if (n.issueId() != null) {
            List<BlockElement> buttons = new ArrayList<>();
            buttons.add(button(n.issueId(), IssueStatus.IN_PROGRESS, "진행 중", null));
            buttons.add(button(n.issueId(), IssueStatus.RESOLVED, "해결", "primary"));
            buttons.add(button(n.issueId(), IssueStatus.WONT_FIX, "보류", "danger"));
            blocks.add(ActionsBlock.builder().blockId("issue_" + n.issueId()).elements(buttons).build());
        }
        return blocks;
    }

    private ButtonElement button(long issueId, IssueStatus status, String label, String style) {
        return ButtonElement.builder()
                .actionId(ACTION_PREFIX + status.name().toLowerCase())
                .text(PlainTextObject.builder().text(label).build())
                .value(issueId + ":" + status.name())
                .style(style)
                .build();
    }
}
