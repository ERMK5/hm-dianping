package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    //代理对象（事务）保证@Transactional生效
    private IVoucherOrderService proxy;
    //Lua脚本相关
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);// 指定Lua脚本返回值类型
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();// 创建一个单线程供后续异步创建订单

    /**
     * 秒杀优惠券下单，执行Lua脚本判断是否有购买资格，有购买资格则保存下单信息到数据库以及阻塞队列
     *
     * @param voucherId
     */
    @Override
    public Long seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        //生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 执行Lua脚本查看是否有购买资格，有购买资格则在Lua脚本里下单到消息队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        int r = result.intValue();
        // 不为0，无购买资格
        if (r == 1) {
            throw new BizException("库存不足");
        }
        if (r == 2) {
            throw new BizException("不能重复下单");
        }

        // 获取代理对象（事务）保证@Transactional生效，供后续创建订单使用
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return orderId;
    }

    //创建子线程，让子线程根据阻塞队列里的信息创建订单
    @PostConstruct// 在 Bean 完成依赖注入之后，执行初始化方法
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";// 消息队列名称
        @Override
        public void run() {
            // 初始化消费者组
            try {
                // 创建消费者组，如果组已存在则忽略错误
                stringRedisTemplate.opsForStream().createGroup(queueName, "g1");
            } catch (Exception e) {
                log.warn("消费者组 g1 已存在，跳过创建");
            }
            while (true) {
                try {
                    // 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 消息队列里没信息，获取失败，继续下一次循环
                    if (list == null || list.isEmpty()){
                        continue;
                    }
                    // 消息队列里有信息，获取成功
                    // 取出list里的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();// 键值对集合
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();// 异常情况下读取pending-list逻辑
                }
            }
        }

        /**
         * 异常情况下读取pending-list逻辑
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName,ReadOffset.from("0"))
                    );
                    // pending-list里没信息，代表没有异常消息，结束该循环流程
                    if (list == null || list.isEmpty()){
                        break;
                    }
                    // pending-list里有信息，获取成功，
                    // 取出list里的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();// 键值对集合
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }


    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);// 阻塞队列
    //创建子线程，让子线程根据阻塞队列里的信息创建订单
    @PostConstruct// 在 Bean 完成依赖注入之后，执行初始化方法
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();//获取和删除该队列的头部，如果没有元素则等待获取，即阻塞
                    // 2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    /**
     * 处理优惠券秒杀订单
     *
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户id（由于是子线程，不能用ThreadLocal取）
        Long userId = voucherOrder.getUserId();
        // 创建锁（用Redisson的锁）
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败
            log.error("一人限购一单，不允许重复下单");
            return;
        }
        // 获取锁成功
        try {
            // 用优惠券创建订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 同一个用户加一个锁，不同用户加的是不同的锁，锁的对象应该是用户id，id值一样的才加锁
    /**
     * 真正创建优惠券秒杀订单
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单，判断该用户是否已经下过该优惠券订单(异步线程执行，不能再ThreadLocal里取用户id)
        Long userId = voucherOrder.getUserId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买，本优惠券每人限购一张");
        }

        // 优惠券余量充足则扣减库存（乐观锁）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("业务异常，扣减优惠券数量失败");
        }

        // 创建订单
        save(voucherOrder);
    }



    /*@Override
    public Long seckillVoucher(Long voucherId) {
        // 判断是否在限时范围内，没在直接返回异常
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();

        if (now.isBefore(beginTime) || now.isAfter(endTime)) {
            throw new BizException("优惠券不支持抢购，请注意起始时间");
        }

        // 在限时范围内，判断该优惠券库存是否充足，不充足返回错误信息
        if (voucher.getStock() < 1) {
            throw new BizException("优惠券无余量");
        }

        Long userId = UserHolder.getUser().getId();

        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 创建锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败
            throw new BizException("一人限购一单，不允许重复下单");
        }
        // 获取锁成功
        try {
            // 获取代理对象（事务）保证@Transactional生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 用优惠券创建订单，返回订单id
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }

    }*/

}
