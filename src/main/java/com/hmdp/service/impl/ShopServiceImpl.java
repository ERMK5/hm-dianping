package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * 根据id查询商铺信息
     *
     * @param id
     * @return
     */
    @Override
    public Shop queryById(Long id) {
        // 缓存空对象解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);

        return shop;
    }

    /**
     * 根据id查询商铺信息（缓存空对象解决缓存穿透）
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
     * 根据id查询商铺信息（互斥锁解决缓存击穿）
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
            lockKey = LOCK_SHOP_KEY + id;
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

    // 线程池对象，这个对象里预先开好 10 条线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询商铺信息（逻辑过期解决缓存击穿）
     *
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 从Redis查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 情况1：缓存未命中，返回异常（逻辑上不会出现这种情况）
        if (StrUtil.isBlank(shopJson)) {
            throw new BizException("服务异常");
        }

        // 缓存命中，将缓存里的JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        // 情况2：判断缓存是否过期，未过期直接返回有效数据
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 情况3：逻辑过期，需要缓存重建
        // 抢锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 拿到锁的线程new一个新线程访问数据库重建缓存，自己返回旧数据
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // DoubleCheck防止多次无效更新
        String shopJsonAgain = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJsonAgain)) {
            return shop;
        }
        // 其余线程没抢到锁时直接返回旧数据
        return shop;
    }

    /**
     * 将商铺信息写入Redis
     *
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id, Long expireSeconds) {
        String cacheKey = CACHE_SHOP_KEY + id;
        // 查询店铺数据
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        String jsonStr = JSONUtil.toJsonStr(redisData);
        // 写入Redis
        stringRedisTemplate.opsForValue().set(cacheKey, jsonStr);
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
