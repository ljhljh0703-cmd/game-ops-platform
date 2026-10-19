// 대기열에서 보낼 때가 된 알림을 집어 보낸다 — 실패하면 간격을 늘려 다시, 끝내 안 되면 FAILED
package com.ljh.gameops.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OutboxDispatcher(OutboxRepository repository, NotificationRenderer renderer, NotificationSender sender,
                            OutboxProperties props, TransactionTemplate tx) {
        this.repository = repository;
        this.renderer = renderer;
        this.sender = sender;
        this.props = props;
        this.tx = tx;
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
                    sender.send(renderer.render(m));
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
