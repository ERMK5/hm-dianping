package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
        // 解决缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        return shop;
    }

    /**
     * 根据id查询Redis与数据库的商铺信息（解决了缓存穿透）
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 从Redis查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 情况1：缓存存在有效商铺数据，直接解析返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 情况2：key存在但值为空串""（之前缓存了空占位），代表店铺确实不存在，拒绝访问数据库
        if (shopJson != null) {
            throw new BizException("店铺不存在！");
        }
        // 情况3：Redis不存在该key（缓存未命中），需要查询数据库
        Shop shop = getById(id);

        // 数据库未查到店铺，写入空字符串到Redis（缓存空值防穿透），再抛出异常
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            throw new BizException("店铺不存在！");
        }
        // 数据库查到商铺，写入Redis缓存
        String shopStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key, shopStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回数据
        return shop;
    }

    /**
     * 根据id查询Redis与数据库的商铺信息（互斥锁解决缓存击穿）
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String lockKey = null;
        Shop shop = null;
        String cacheKey = CACHE_SHOP_KEY + id;
        try {
            // 1.先查缓存
            String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
            // 缓存已有有效数据，直接返回，不用查库
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 缓存是空值（空字符串""），店铺不存在
            if (shopJson != null) {
                throw new BizException("店铺不存在！");
            }

            // 2.缓存失效，缓存为空，抢互斥锁
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            long deadline = System.currentTimeMillis() + 2000; // 最多等待2秒
            boolean isLock = false;
            // 自旋抢锁 + 2秒超时机制
            // 没抢到锁
            while (!(isLock = tryLock(lockKey)) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            // 超时仍未拿到锁
            if (!isLock) {
                throw new BizException("访问人数过多，请稍后重试");
            }

            // 抢到锁之后，再查一遍缓存DoubleCheck
            String shopJsonAgain = stringRedisTemplate.opsForValue().get(cacheKey);
            // 缓存已有有效数据，直接返回，不用查库
            if (StrUtil.isNotBlank(shopJsonAgain)) {
                return JSONUtil.toBean(shopJsonAgain, Shop.class);
            }
            // 缓存是空值（空字符串""），店铺不存在
            if (shopJsonAgain != null) {
                throw new BizException("店铺不存在！");
            }

            // 3.缓存依旧为空，自己去查库、重建缓存
            shop = getById(id);
            if (shop == null) {
                // 缓存空值防止穿透
                stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                throw new BizException("店铺不存在！");
            }
            // 写入缓存
            String shopStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(cacheKey, shopStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 必须判断锁不为null再释放，防止lockKey为null执行unlock报错
            if (lockKey != null) {
                unlock(lockKey);
            }
        }
        return shop;
    }

    /**
     * 更新商铺信息
     *
     * @param shop
     */
    @Override
    @Transactional
    public void update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            throw new BizException("店铺id不能为空！");
        }
        // 先修改数据库
        // 根据id修改店铺
        updateById(shop);
        // 再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    }

    /**
     * 尝试获取锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
