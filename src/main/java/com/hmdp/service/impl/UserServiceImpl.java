package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.util.validation.metadata.NamedObject;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验，生成，保存，返回
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String s = RandomUtil.randomNumbers(4);
        session.setAttribute("code", s);
        log.debug("发送短信验证码成功为{}", s);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        String code = (String) session.getAttribute("code");
//        String code1 = loginForm.getCode();
//
//
//        if(code1 == code) {
//            return Result.ok();
//        }
//        return Result.fail("登录失败");
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        Object cachecode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cachecode == null) {
            return Result.fail("验证码到期");
        }
        if (!cachecode.toString().equals(code)) {
            return Result.fail("验证码错误");
        }
        //判断用户是否存在
        User userp = query().eq("phone", phone).one();
        if (userp == null) {
            User user = createWithPhone(phone);
        }
        session.setAttribute("user", user);

        return Result.ok();



    }

    private User createWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;

    }
}
