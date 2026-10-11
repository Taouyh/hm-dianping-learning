package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.segments.MergeSegments;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private final StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;


    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return Result.fail("当前未登录，无法关注");
        }
        //2.获取当前用户的id
        Long userId = user.getId();
        String key = "follows:" + userId;
        //3.关注当前用户
        //3.1 follow = true 关注
        if (BooleanUtil.isTrue(isFollow)){
            //写入tb_follow 表 insert into tb_user (user_id,follow_user_id)
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            follow.setCreateTime(LocalDateTime.now());
            boolean isSuccess = save(follow);
            //如果成功，把关注的id写入到redis
            if (isSuccess){
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            //取关， delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }


    @Override
    public Result isFollowed(Long followUserId) {
        //select count(*) from tb_follow where follow_user_id = ? and user_id = ?
        //如果条数>0 就是关注了，否则就不是
        Long id = UserHolder.getUser().getId();
        Integer count = query().eq("follow_user_id", followUserId).eq("user_id", id).count();
        return Result.ok(count > 0);

    }

    @Override
    public Result commonFollow(Long followUserId) {
        //共同关注
        UserDTO current_user = UserHolder.getUser();
        if (current_user == null){
            return Result.fail("未登录，无法查看");
        }
        //当前用户的id
        Long userId = current_user.getId();
        //关注的博主的key1
        String key1 = "follows:"+followUserId;
        //当前用户的key2
        String key2 = "follows:"+userId;
        //取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //将String 类型转为 Long
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id查询用户
        List<User> users = userService.listByIds(ids);
        //User -> UserDTO
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
