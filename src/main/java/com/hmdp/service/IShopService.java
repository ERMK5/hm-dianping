package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺信息
     *
     * @param id
     * @return
     */
    Shop queryById(Long id);

    /**
     * 更新商铺信息
     *
     * @param shop
     */
    void update(Shop shop);

    /**
     * 根据类型查询店铺（可能按地理位置排序），分页查询
     *
     * @param typeId
     * @param current 页码
     * @param x
     * @param y
     * @return
     */
    List<Shop> queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
