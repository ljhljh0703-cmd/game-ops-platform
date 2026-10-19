// 운영 대상 게임 엔티티
package com.ljh.gameops.game;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "game")
public class Game {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected Game() {}

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
