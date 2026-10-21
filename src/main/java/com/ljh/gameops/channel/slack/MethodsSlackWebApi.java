// 실제 슬랙 Web API 로 거는 구현 — 봇 토큰 하나로 views.open · chat.update 를 부른다(추가 권한 불필요: chat:write 는 이미 있음)
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.notify.IssueCard;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.model.view.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MethodsSlackWebApi implements SlackWebApi {
    private static final Logger log = LoggerFactory.getLogger(MethodsSlackWebApi.class);
    private final MethodsClient client;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    public MethodsSlackWebApi(MethodsClient client) { this.client = client; }

    @jakarta.annotation.PreDestroy
    void stop() { pool.shutdown(); }

    @Override
    public boolean openView(String triggerId, View view) {
        try {
            var r = client.viewsOpen(ViewsOpenRequest.builder().triggerId(triggerId).view(view).build());
            if (!r.isOk()) log.warn("슬랙 입력 창 열기 실패: {} {}", r.getError(), r.getResponseMetadata());
            return r.isOk();
        } catch (Exception e) {
            log.warn("슬랙 입력 창 열기 실패: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void updateCard(String channel, String ts, IssueCard card) {
        update(channel, ts, card.gameName() + " #" + card.issueId() + " · " + card.title(), SlackCards.card(card, null));
    }

    @Override
    public void updateRulesCard(String channel, String ts, com.ljh.gameops.notify.RuleChangeCard card) {
        update(channel, ts, "분류 규칙 #" + card.versionId(), SlackCards.rulesCard(card));
    }

    @Override
    public void updatePatchCard(String channel, String ts, com.ljh.gameops.notify.PatchCard card) {
        update(channel, ts, card.title(), SlackCards.patchCard(card));
    }

    @Override
    public void updateDigestCard(String channel, String ts, com.ljh.gameops.notify.DigestCard card) {
        update(channel, ts, "최근 피드백 정리 #" + card.digestId(), SlackCards.digestCard(card));
    }

    @Override
    public boolean publishHome(String userId, View view) {
        try {
            var r = client.viewsPublish(com.slack.api.methods.request.views.ViewsPublishRequest.builder().userId(userId).view(view).build());
            if (!r.isOk()) log.warn("슬랙 홈 올리기 실패: {} {}", r.getError(), r.getResponseMetadata());
            return r.isOk();
        } catch (Exception e) {
            log.warn("슬랙 홈 올리기 실패: {}", e.getMessage());
            return false;
        }
    }

    private void update(String channel, String ts, String text, com.slack.api.model.Attachment attachment) {
        if (channel == null || ts == null) return;
        pool.submit(() -> {
            try {
                var r = client.chatUpdate(ChatUpdateRequest.builder().channel(channel).ts(ts).text(text)
                        .attachments(List.of(attachment)).build());
                if (!r.isOk()) log.warn("슬랙 카드 바꾸기 실패: {}", r.getError());
            } catch (Exception e) {
                log.warn("슬랙 카드 바꾸기 실패: {}", e.getMessage());
            }
        });
    }
}
