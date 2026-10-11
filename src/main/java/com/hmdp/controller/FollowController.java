package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private  IFollowService ifollowService;

    /**
     * 关注与取关
     * @param followUserId 要关注的用户的id
     * @param isFollow true:关注 false: 取关
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result followBlogUser(@PathVariable("id") Long followUserId,@PathVariable("isFollow") Boolean isFollow){
        return ifollowService.follow(followUserId,isFollow);
    }

    /**
     * 查看是否关注了，以便渲染前端
     * @param followUserId
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result isFollowed(@PathVariable("id") Long followUserId){
        return ifollowService.isFollowed(followUserId);
    }

    /**
     * 共同关注
     * @param followUserId
     * @return
     */
    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long followUserId){
        //关注的用户他所关注的用户的集合和我关注的用户的集合的交集
        return ifollowService.commonFollow(followUserId);
    }

}
