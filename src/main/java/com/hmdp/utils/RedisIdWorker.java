package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于Redis的id生成器，生成订单id
 */
@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    // 开始时间戳（UTC时间2026年1月1日0时0分0秒）
    private static Long BEGIN_TIMESTAMP = 1767225600L;
    private static int COUNT_BITS = 32;// 序列号位数，用于位运算

    public long nextId(String keyPrefix) {
        // 1.生成时间戳（31bit）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号（32bit）
        // 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 自增长
        long count = stringRedisTemplate.opsForValue().increment("idr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;

    }
}
