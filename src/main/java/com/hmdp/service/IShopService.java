package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询Redis与数据库的商铺信息
     * @param id
     * @return
     */
    Shop queryById(Long id);

    /**
     * 更新商铺信息
     * @param shop
     */
    void update(Shop shop);
}
