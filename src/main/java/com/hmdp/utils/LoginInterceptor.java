package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.management.Query;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * @author qjl
 * @create 2022-10-02 17:40
 */
public class LoginInterceptor implements HandlerInterceptor {
//    前置校验
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        if (user == null){
            response.setStatus(401);
            return false;
        }
//        将信息保存到当前的线程里面
        UserHolder.saveUser((UserDTO) user);
        return true;



    }

//    校验结束将信息删除,避免线程泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
