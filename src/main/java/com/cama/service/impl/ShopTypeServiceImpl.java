package com.cama.service.impl;

import com.cama.entity.ShopType;
import com.cama.mapper.ShopTypeMapper;
import com.cama.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopTypeService typeService;

    @Override
    public List<ShopType> queryTypeList() {
//        stringRedisTemplate.opsForHash()

        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        return typeList;
    }
}
