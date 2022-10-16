package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.util.validation.metadata.NamedObject;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    //    现在将采用redis代替session存储

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验，生成，保存，返回
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(4);

//        保存验证码
//        采用session的方式进行保存：session.setAttribute("code", s);
//        采用redis的方式进行存储数据，以手机号码为key，随机生成的验证码为value.设置有效期为两分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送短信验证码成功为{}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
//        Object cachecode = session.getAttribute("code");
//        使用redis取数据进行校验
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String code = loginForm.getCode();
        if (cachecode == null) {
            return Result.fail("验证码到期");
        }
        if (!cachecode.equals(code)) {
            return Result.fail("验证码错误");
        }
        //判断用户是否存在
        User user = query().eq("phone", phone).one();
        if (user == null) {
            //不存在就创建新的对象
            user = createWithPhone(phone);
        }
//        随机生成token
        String token = UUID.randomUUID().toString(true);
        //存储token的值，有效期是30分钟
//        Long id = UserHolder.getUser().getId();
        stringRedisTemplate.opsForValue().set("token", token,30,TimeUnit.MINUTES);
        //将对象保存到redis中,token作为key，usr作为value.使用哈希结构,将userDTO转为map结构
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将所有的字段转换为字符串
        Map<String, Object> map = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((name,value) ->value.toString()));
//        Map<String, Object> map = BeanUtil.beanToMap(userDTO);
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,map);
        //避免token的存在时间太长，设置有效期30分钟，目前是只要30分钟后就会清理。这样存在一个问题，如果一直活跃也会到30分钟被清理，需要
        //如果进行了拦截器验证就需要更新有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,30,TimeUnit.MINUTES);
        //如果一直活动就会更新有效期
//        stringRedisTemplate.expire("token",30,TimeUnit.MINUTES);


        //存储
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        return Result.ok(token);



    }

    @Override
    public Result logout() {
        String token = stringRedisTemplate.opsForValue().get(" ");
//        Long id = UserHolder.getUser().getId();
        stringRedisTemplate.delete(LOGIN_USER_KEY+token);
        stringRedisTemplate.delete("token");
        return Result.ok("退出成功");
    }

    /**
     * 完成用户签到功能
     * @return
     */
    @Override
    public Result sign() {
        //获取用户信息，日期信息，拼接key，获取今天是本月的第几天，写入redis
        Long userId = UserHolder.getUser().getId();
        LocalDateTime time = LocalDateTime.now();
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int day = time.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, day-1,true);
        return Result.ok();
    }

    /**
     * 签到统计
     * @return
     */
    @Override
    public Result signcount() {
        //获取今天是本月的第几天，获取截至今天的签到记录，返回一个十进制数，遍历，与1做与运算，判断这个位是否为0，0结束，计数器加1
        //获取用户信息，日期信息，拼接key，获取今天是本月的第几天，写入redis
        Long userId = UserHolder.getUser().getId();
        LocalDateTime time = LocalDateTime.now();
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int day = time.getDayOfMonth();
        //获取截至当前日期的位数
        List<Long> result = stringRedisTemplate.opsForValue().
                bitField(key,
                        BitFieldSubCommands.create().
                                get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long aLong = result.get(0);
        if (aLong == 0 || aLong == null){
            return Result.ok(0);
        }
        int count = 0;
        while (true){
            if((aLong & 1) == 0){
                break;
            }else {
                count++;
            }
            aLong >>>=1;
        }

        return Result.ok(count);
    }

    private User createWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;

    }
}

//package com.hmdp.service.impl;
//
//import cn.hutool.core.bean.BeanUtil;
//import cn.hutool.core.bean.copier.CopyOptions;
//import cn.hutool.core.lang.UUID;
//import cn.hutool.core.util.RandomUtil;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.hmdp.dto.LoginFormDTO;
//import com.hmdp.dto.Result;
//import com.hmdp.dto.UserDTO;
//import com.hmdp.entity.User;
//import com.hmdp.mapper.UserMapper;
//import com.hmdp.service.IUserService;
//import com.hmdp.utils.RegexUtils;
//import com.hmdp.utils.UserHolder;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.data.redis.connection.BitFieldSubCommands;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.stereotype.Service;
//
//import javax.annotation.Resource;
//import javax.servlet.http.HttpSession;
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.TimeUnit;
//
//import static com.hmdp.utils.RedisConstants.*;
//import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
//
///**
// * <p>
// * 服务实现类
// * </p>
// *
// * @author 虎哥
// * @since 2021-12-22
// */
//@Slf4j
//@Service
//public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
//
//    @Resource
//    private StringRedisTemplate stringRedisTemplate;
//
//    @Override
//    public Result sendCode(String phone, HttpSession session) {
//        // 1.校验手机号
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            // 2.如果不符合，返回错误信息
//            return Result.fail("手机号格式错误！");
//        }
//        // 3.符合，生成验证码
//        String code = RandomUtil.randomNumbers(6);
//
//        // 4.保存验证码到 session
//        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
//
//        // 5.发送验证码
//        log.debug("发送短信验证码成功，验证码：{}", code);
//        // 返回ok
//        return Result.ok();
//    }
//
//    @Override
//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        // 1.校验手机号
//        String phone = loginForm.getPhone();
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            // 2.如果不符合，返回错误信息
//            return Result.fail("手机号格式错误！");
//        }
//        // 3.从redis获取验证码并校验
//        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
//        String code = loginForm.getCode();
//        if (cacheCode == null || !cacheCode.equals(code)) {
//            // 不一致，报错
//            return Result.fail("验证码错误");
//        }
//
//        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
//        User user = query().eq("phone", phone).one();
//
//        // 5.判断用户是否存在
//        if (user == null) {
//            // 6.不存在，创建新用户并保存
//            user = createWithPhone(phone);
//        }
//
//        // 7.保存用户信息到 redis中
//        // 7.1.随机生成token，作为登录令牌
//        String token = UUID.randomUUID().toString(true);
//        // 7.2.将User对象转为HashMap存储
//        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
//                CopyOptions.create()
//                        .setIgnoreNullValue(true)
//                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
//        // 7.3.存储
//        String tokenKey = LOGIN_USER_KEY + token;
//        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//        // 7.4.设置token有效期
//        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
//
//        // 8.返回token
//        return Result.ok(token);
//    }
