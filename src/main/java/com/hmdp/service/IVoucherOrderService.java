package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 限时限量优惠券下单
     *
     * @param voucherId
     */
    Long seckillVoucher(Long voucherId);


    /**
     * 用秒杀券新增订单
     *
     * @param voucherOrder
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
