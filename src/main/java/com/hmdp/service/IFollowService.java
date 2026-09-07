package com.hmdp.service;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    // 关注或取关
    void follow(Long followUserId, Boolean isFollow);
    // 查询是否关注
    Boolean isFollow(Long followUserId);
    // 查询共同关注
    List<UserDTO> followCommons(Long id);
}
