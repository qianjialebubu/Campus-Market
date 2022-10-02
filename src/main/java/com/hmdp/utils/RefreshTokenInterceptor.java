package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author qjl
 * @create 2022-10-02 17:40
 */
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //    前置校验

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        修改为使用redis的方式，从请求头中获取token
        String token = request.getHeader("authorization");
        log.info("token: " + token);
        System.out.println(token);
//        如果为空进行拦截
        if (StrUtil.isBlank(token)) {
//            response.setStatus(401);
            return true;
        }
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);


//        HttpSession session = request.getSession();

//        Object user = session.getAttribute("user");
        if (userMap.isEmpty()){
//            response.setStatus(401);
            return true;
        }
//        将信息保存到当前的线程里面，将获取的Hash数据转换为UserDTO
//        UserHolder.saveUser((UserDTO) user);
//        将Hash转换为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
//        刷新token的有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,30, TimeUnit.MINUTES);
//        log.info();
        return true;



    }

//    校验结束将信息删除,避免线程泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
