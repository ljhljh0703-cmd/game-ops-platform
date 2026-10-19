// API 예외를 일정한 응답 형식으로 바꾼다
package com.ljh.gameops.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    /** 같은 내용의 열린 이슈가 이미 있는데 닫힌 이슈를 다시 열거나, 다른 요청과 동시에 고친 경우. */
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class})
    ResponseEntity<Map<String, String>> conflict(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "다른 변경과 겹쳤습니다. 같은 내용의 열린 이슈가 있거나 동시에 수정됐습니다. 새로고침 뒤 다시 시도하세요."));
    }

    /** 지금 상태에서는 할 수 없는 요청(예: 제보가 하나뿐인 이슈에서 떼어내기). */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, String>> invalidState(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
