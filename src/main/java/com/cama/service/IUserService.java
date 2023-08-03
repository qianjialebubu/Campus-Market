package com.cama.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cama.dto.LoginFormDTO;
import com.cama.dto.Result;
import com.cama.entity.User;

import javax.servlet.http.HttpSession;


public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout();

    Result sign();

    Result signcount();
}
