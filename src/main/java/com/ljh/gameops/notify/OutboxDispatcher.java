// 대기열에서 보낼 때가 된 알림을 집어 보낸다 — 실패하면 간격을 늘려 다시, 끝내 안 되면 FAILED
package com.ljh.gameops.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository repository;
    private final NotificationRenderer renderer;
    private final NotificationSender sender;
    private final OutboxProperties props;
    private final TransactionTemplate tx;
    private final ObjectProvider<PlayerNotifier> players;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    PatchNoteDelivery patches;

    public OutboxDispatcher(OutboxRepository repository, NotificationRenderer renderer, NotificationSender sender,
                            OutboxProperties props, TransactionTemplate tx, ObjectProvider<PlayerNotifier> players) {
        this.repository = repository;
        this.renderer = renderer;
        this.sender = sender;
        this.props = props;
        this.tx = tx;
        this.players = players;
    }

    /** 플레이어 답장은 슬랙이 아니라 디스코드로. 디스코드를 끈 채면 보낼 곳이 없으니 보낸 것으로 친다(쌓아 두지 않음). */
    private void deliver(OutboxMessage m) {
        if (m.getType() == NotificationType.PATCH_PUBLISH) {
            if (patches == null) throw new IllegalStateException("패치노트 기능이 없어요");
            patches.deliver(renderer.patchId(m));
            return;
        }
        if (m.getType() == NotificationType.PLAYER_RESOLVED) {
            PlayerNotifier p = players.getIfAvailable();
            if (p == null) {
                log.debug("디스코드가 꺼져 있어 제보자 답장을 건너뜁니다 id={}", m.getId());
                return;
            }
            long issueId = renderer.render(m).issueId();
            int n = p.issueResolved(issueId);
            log.info("이슈 #{} 해결 답장 {}건", issueId, n);
            return;
        }
        sender.send(renderer.render(m));
    }

    @Scheduled(fixedDelayString = "${gameops.outbox.poll-ms}")
    public void poll() {
        dispatchDue();
    }

    /** 한 묶음을 보낸다. 보낸 건수를 돌려준다(테스트·모니터링용). */
    public int dispatchDue() {
        Integer sent = tx.execute(status -> {
            List<OutboxMessage> due = repository.lockDue(props.batchSize());
            int ok = 0;
            for (OutboxMessage m : due) {
                try {
                    deliver(m);
                    m.markSent();
                    ok++;
                } catch (RuntimeException e) {
                    m.markFailedAttempt(e.getMessage(), props.maxAttempts(), props.baseBackoffMs());
                    log.warn("알림 전송 실패 id={} 시도={} 상태={}: {}", m.getId(), m.getAttempts(), m.getStatus(), e.getMessage());
                }
                repository.save(m);
            }
            return ok;
        });
        return sent == null ? 0 : sent;
    }
}
