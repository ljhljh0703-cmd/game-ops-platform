// 알림을 대기열에 넣는다 — 호출한 쪽 트랜잭션에 같이 묶인다
package com.ljh.gameops.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class OutboxService {
    private final OutboxRepository repository;
    private final ObjectMapper mapper;

    public OutboxService(OutboxRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NotificationType type, Map<String, Object> payload) {
        try {
            repository.save(new OutboxMessage(type, mapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public void enqueueStandalone(NotificationType type, Map<String, Object> payload) {
        enqueue(type, payload);
    }
}
