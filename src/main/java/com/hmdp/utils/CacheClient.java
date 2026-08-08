package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 缓存工具类
 */
@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将任意Java对象序列化为JSON并存储在String类型的key中，并且可以设置TTL过期时间
     *
     * @param key   Redis的key
     * @param value Redis的value
     * @param time  过期时间
     * @param unit  时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意Java对象序列化为JSON并存储在String类型的key中，并且可以设置逻辑过期时间，
     * 用于处理缓存击穿问题
     *
     * @param key
     * @param value
     * @param time  用于逻辑过期时间
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 根据指定的 key 查询缓存，并反序列化为指定类型，
     * 利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix  key前缀
     * @param id
     * @param type       返回类型的字节码文件
     * @param dbFallback 根据id查询数据库函数
     * @param time       Redis过期时间
     * @param unit       Redis过期时间单位
     * @param <R>        返回类型
     * @param <ID>       id的类型
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从Redis查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 情况1：缓存存在有效商铺数据，直接解析返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 情况2：key存在但值为空串""（之前缓存了空占位），代表店铺确实不存在，拒绝访问数据库
        if (json != null) {
            throw new BizException("不存在该数据");
        }
        // 情况3：Redis不存在该key（缓存未命中），需要查询数据库
        R r = dbFallback.apply(id);

        // 数据库未查到店铺，写入空字符串到Redis（缓存空值防穿透），再抛出异常
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            throw new BizException("不存在该数据");
        }
        // 数据库查到商铺，写入Redis缓存
        this.set(key, r, time, unit);
        // 返回数据
        return r;
    }


    // 线程池对象，这个对象里预先开好 10 条线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     *
     * @param
     * @return
     */
    public <ID, R> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从Redis查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 情况1：缓存未命中，返回异常（逻辑上不会出现这种情况）
        if (StrUtil.isBlank(json)) {
            throw new BizException("服务异常");
        }

        // 缓存命中，将缓存里的JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);

        // 情况2：判断缓存是否过期，未过期直接返回有效数据
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 情况3：逻辑过期，需要缓存重建
        // 抢锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);

        // 拿到锁的线程new一个新线程访问数据库重建缓存，自己返回旧数据
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // DoubleCheck防止多次无效更新
                    String newJson = stringRedisTemplate.opsForValue().get(key);
                    RedisData newRedisData = JSONUtil.toBean(newJson, RedisData.class);
                    JSONObject newData = (JSONObject) newRedisData.getData();
                    R newR = JSONUtil.toBean(newData, type);
                    // 判断缓存是否过期
                    LocalDateTime newExpireTime = newRedisData.getExpireTime();
                    // 缓存没过期，证明缓存已经重新写入成功
                    if (newExpireTime.isAfter(LocalDateTime.now())) {
                        return;
                    }
                    // 缓存重建，先查数据库
                    R r1 = dbFallback.apply(id);
                    // 再写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    log.error("{}缓存重建失败", id, e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 主线程，其余线程（除重建缓存的线程外）直接返回旧数据
        return r;
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
