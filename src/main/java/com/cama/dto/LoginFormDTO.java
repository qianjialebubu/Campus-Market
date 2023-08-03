package com.cama.dto;

import lombok.Data;

/**
 * 登录功能对象，包含手机号码，密码以及验证码
 */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
