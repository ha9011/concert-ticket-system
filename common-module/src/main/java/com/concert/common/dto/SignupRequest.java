package com.concert.common.dto;

import lombok.Data;

@Data
public class SignupRequest {
    private String email;
    private String code;
    private String password;
    private String name;
}
