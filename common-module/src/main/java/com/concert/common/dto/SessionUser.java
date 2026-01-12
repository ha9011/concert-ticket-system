package com.concert.common.dto;

import com.concert.common.entity.User;
import lombok.Getter;

import java.io.Serializable;

/**
 * 세션에 저장할 최소한의 유저 정보
 * - Serializable을 구현해야 Redis에 저장될 수 있습니다.
 */
@Getter
public class SessionUser implements Serializable {
    private final Long id;
    private final String email;
    private final String name;

    public SessionUser(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.name = user.getName();
    }
}
