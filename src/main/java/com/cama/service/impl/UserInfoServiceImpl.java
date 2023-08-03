package com.cama.service.impl;

import com.cama.entity.UserInfo;
import com.cama.mapper.UserInfoMapper;
import com.cama.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
