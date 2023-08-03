package com.cama.controller;


import com.cama.dto.Result;
import com.cama.entity.ShopType;
import com.cama.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;


@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    /**
     * 目前使用的是mybatisplus的查询功能，改造成使用redis的缓存的方式
     */
    public Result queryTypeList() {

        /**
         * 返回列表进行排序操作
         * 先返回查找的列表再进行排序的操作
         */
//        List<ShopType> typeList = typeService.queryTypeList();
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        return Result.ok(typeList);
    }
}
