package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询Redis与数据库的商铺信息
     *
     * @param id
     * @return
     */
    @Override
    public Shop queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 从Redis查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中则返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 未命中则根据id查询数据库
        Shop shop = getById(id);
        // 数据库没有该数据，返回错误
        if (shop == null) {
            throw new BizException("店铺不存在！");
        }
        // 数据库有该数据，写入Redis
        String shopStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key, shopStr);
        // 返回数据
        return shop;
    }
}
