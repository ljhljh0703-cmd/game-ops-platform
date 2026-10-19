// 슬랙 메시지 모양 — 채널 고르기와 상태 버튼 만들기(외부 호출 없음, 테스트 대상)
package com.ljh.gameops.channel.slack;

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

    public SlackMessageBuilder(SlackProperties props) { this.props = props; }

    public String channelFor(RenderedNotification n) {
        String urgent = props.channels() == null ? null : props.channels().urgent();
        String def = props.channels() == null ? null : props.channels().defaultChannel();
        boolean high = n.severity() == Severity.HIGH || n.severity() == Severity.CRITICAL;
        if (high && urgent != null && !urgent.isBlank()) return urgent;
        if (def == null || def.isBlank()) throw new IllegalStateException("슬랙 기본 채널이 설정되지 않았습니다");
        return def;
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
