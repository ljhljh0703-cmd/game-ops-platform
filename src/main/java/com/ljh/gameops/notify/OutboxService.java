// 알림을 대기열에 넣는다 — 호출한 쪽 트랜잭션에 같이 묶인다
package com.ljh.gameops.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
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

    /**
     * 같은 dedupeKey 로는 한 번만 넣는다. 서버가 여러 대라 같은 예약 작업이 동시에 돌아도 알림은 하나.
     * 넣었으면 true, 이미 있었으면 false.
     */
    public boolean enqueueOnce(NotificationType type, Map<String, Object> payload, String dedupeKey) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        try {
            repository.saveAndFlush(new OutboxMessage(type, json, dedupeKey));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
