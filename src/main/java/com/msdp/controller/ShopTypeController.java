package com.msdp.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.msdp.dto.Result;
import com.msdp.entity.ShopType;
import com.msdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author Eason
 * @since 2023-03-15
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate template;

    @GetMapping("list")
    public Result queryTypeList() {
        String key = "shop-type-list";
        String listJson = template.opsForValue().get(key);
        if(StrUtil.isNotBlank(listJson)){
            // System.out.println(listJson);
            List<ShopType> list = JSONUtil.toList(listJson, ShopType.class);
            return Result.ok(list);
        }

        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        String typeListJson = JSONUtil.toJsonStr(typeList);
        template.opsForValue().set(key, typeListJson);
        return Result.ok(typeList);
    }
}
