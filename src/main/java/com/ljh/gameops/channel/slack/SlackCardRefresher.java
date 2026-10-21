// 어디서 바꿨든(슬랙 버튼·API·AI 에이전트·플러그인 명령) 슬랙 카드를 그 자리에서 바꾼다.
// 바뀐 일이 커밋된 뒤, 뒤에서(별도 스레드) 기억해 둔 카드 위치마다 지금 상태로 다시 그린다. 「누가 무엇을 언제」는 카드에 남긴다.
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.classify.RuleVersionRepository;
import com.ljh.gameops.notify.DigestCardFactory;
import com.ljh.gameops.notify.DigestChanged;
import com.ljh.gameops.notify.IssueCardFactory;
import com.ljh.gameops.notify.IssueChanged;
import com.ljh.gameops.notify.RuleChangeCard;
import com.ljh.gameops.notify.RulesChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class SlackCardRefresher {
    private static final Logger log = LoggerFactory.getLogger(SlackCardRefresher.class);
    private final SlackMessageStore store;
    private final IssueCardFactory cards;
    private final RuleVersionRepository versions;
    private final SlackWebApi web;
    private final Clock clock;
    private final DigestCardFactory digestCards;
    // 커밋 뒤 처리 단계에서는 새 DB 쓰기가 커밋되지 않을 수 있어, 아예 다른 스레드에서 한다(한 줄로 차례대로)
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    public SlackCardRefresher(SlackMessageStore store, IssueCardFactory cards, RuleVersionRepository versions, SlackWebApi web, Clock clock,
                              DigestCardFactory digestCards) {
        this.digestCards = digestCards;
        this.store = store;
        this.cards = cards;
        this.versions = versions;
        this.web = web;
        this.clock = clock;
    }

    @jakarta.annotation.PreDestroy
    void stop() { pool.shutdown(); }

    String line(String what, String actor) {
        return what + " · " + SlackCards.actorLabel(actor) + " · " + SlackCards.when(OffsetDateTime.now(clock));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIssueChanged(IssueChanged e) {
        pool.submit(() -> {
            try {
                if (e.channel() != null) store.remember(SlackMessageStore.Kind.ISSUE, e.issueId(), e.channel(), e.ts());
                var locs = store.find(SlackMessageStore.Kind.ISSUE, e.issueId());
                if (locs.isEmpty()) return;
                String last = e.what() == null ? locs.get(locs.size() - 1).lastAction() : line(e.what(), e.actor());
                if (e.what() != null) store.setLastAction(SlackMessageStore.Kind.ISSUE, e.issueId(), last);
                var card = cards.current(e.issueId()).withLastAction(last);
                for (var l : locs) web.updateCard(l.channel(), l.ts(), card);
            } catch (RuntimeException ex) {
                log.warn("슬랙 카드 새로 그리기 실패 issue={}: {}", e.issueId(), ex.getMessage());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRulesChanged(RulesChanged e) {
        pool.submit(() -> {
            try {
                if (e.channel() != null) store.remember(SlackMessageStore.Kind.RULES, e.versionId(), e.channel(), e.ts());
                var locs = store.find(SlackMessageStore.Kind.RULES, e.versionId());
                if (locs.isEmpty()) return;
                String last = line(e.what(), e.actor());
                store.setLastAction(SlackMessageStore.Kind.RULES, e.versionId(), last);
                var v = versions.findById(e.versionId()).orElseThrow();
                var card = RuleChangeCard.of(v).withLastAction(last);
                for (var l : locs) web.updateRulesCard(l.channel(), l.ts(), card);
            } catch (RuntimeException ex) {
                log.warn("슬랙 규칙 카드 새로 그리기 실패 #{}: {}", e.versionId(), ex.getMessage());
            }
        });
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.ljh.gameops.notify.PatchNoteDelivery patches;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPatchChanged(com.ljh.gameops.notify.PatchChanged e) {
        if (patches == null) return;
        pool.submit(() -> {
            try {
                if (e.channel() != null) store.remember(SlackMessageStore.Kind.PATCH, e.patchId(), e.channel(), e.ts());
                var locs = store.find(SlackMessageStore.Kind.PATCH, e.patchId());
                if (locs.isEmpty()) return;
                var card = patches.card(e.patchId());
                for (var l : locs) web.updatePatchCard(l.channel(), l.ts(), card);
            } catch (RuntimeException ex) {
                log.warn("슬랙 패치노트 카드 새로 그리기 실패 #{}: {}", e.patchId(), ex.getMessage());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDigestChanged(DigestChanged e) {
        pool.submit(() -> {
            try {
                if (e.channel() != null) store.remember(SlackMessageStore.Kind.DIGEST, e.digestId(), e.channel(), e.ts());
                var locs = store.find(SlackMessageStore.Kind.DIGEST, e.digestId());
                if (locs.isEmpty()) return;
                var card = digestCards.current(e.digestId());
                for (var l : locs) web.updateDigestCard(l.channel(), l.ts(), card);
            } catch (RuntimeException ex) {
                log.warn("슬랙 정리 카드 새로 그리기 실패 #{}: {}", e.digestId(), ex.getMessage());
            }
        });
    }
}
