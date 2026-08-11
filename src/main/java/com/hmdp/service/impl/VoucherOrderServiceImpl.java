package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 限时限量优惠券下单
     *
     * @param voucherId
     */
    @Override
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
        synchronized (userId.toString().intern()) {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    // 同一个用户加一个锁，不同用户加的是不同的锁，锁的对象应该是用户id，id值一样的才加锁
    @Transactional
    public Long createVoucherOrder(Long voucherId) {
        // 一人一单，判断该用户是否已经下过该优惠券订单
        Long userId = UserHolder.getUser().getId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            throw new BizException("用户已经购买，本优惠券每人限购一张");
        }

        // 优惠券余量充足则扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            throw new BizException("业务异常，扣减优惠券数量失败");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(UserHolder.getUser().getId());//用户id
        voucherOrder.setVoucherId(voucherId);//订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        save(voucherOrder);

        // 返回订单id
        return orderId;
    }
}
