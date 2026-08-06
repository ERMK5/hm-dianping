package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> listShopType() {
        String key = "cache:shopType";
        // 在Redis中查询所有店铺类型
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 判断Redis中是否有数据
        // 有数据则封装返回（缓存命中）
        if (shopTypeList != null && !shopTypeList.isEmpty()) {
            return shopTypeList.stream()
                    .map(x-> JSONUtil.toBean(x,ShopType.class))
                    .collect(Collectors.toList());
        }

        // 没有则查找数据库（缓存未命中），orderByAsc保证读出有序
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 将数据添加进Redis中
        List<String> strList = typeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key,strList);
        // 返回数据
        return typeList;
    }
}
