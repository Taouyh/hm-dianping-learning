package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        //查询是否点过赞
        records.forEach(blog -> {
            getUserByBlog(blog);
            queryIsLikedBlog(blog);
        });
        return Result.ok(records);
    }


    private void getUserByBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("博客信息不存在");
        }
        //2.查询用户的信息
        getUserByBlog(blog);
        //查询是否点过赞
        queryIsLikedBlog(blog);
        return Result.ok(blog);
    }

    /**
     * 查询当前用户是否点过赞
     * @param blog
     */
    private void queryIsLikedBlog(Blog blog) {
        //判断当前用户是否点赞过
        UserDTO user = UserHolder.getUser();
        if (user == null)
        {
            // 用户未登录，无需查询是否点赞
            return;
        }
        String key = BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }


    @Override
    public Result queryIsLikeBlog(Long id) {
        //查询当前点赞用户
        //key: 博客的id value: 点赞的用户id，使用set
        //1.2获取当前用户的id
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //1.3查询是否点过赞
        String key = BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //1.3.1没点过，点赞数 + 1，保存userId到redis中
        if (score == null){
            boolean isSuccess = update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                //分数写当前的时间戳
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //1.3.2点过赞，点赞数 - 1，在redis中删除userId
            boolean isSuccess = update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //使用ZSET计算点赞排行榜
        //获取用户的id集合
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return  Result.ok(Collections.emptyList());
        }
        //转换成用户id集合
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //查找用户
        //select * from tb_user where id in (5,1) order by field (id,5,1) 保证顺序是对的
        String idStr = StrUtil.join(",",ids);
        List<User> users = userService.query().in("id",ids).last("ORDER BY FIELD (id,"+idStr+")").list();
        //获取用户DTO
        List<UserDTO> userDTOList = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        //返回
        return Result.ok(userDTOList);
    }

    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        //分页查询
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", userId).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (isSuccess){
            //获取我的粉丝列表
            List<Follow> followList = followService.query().eq("follow_user_id", user.getId()).list();
            for (Follow follow : followList) {
                //获取粉丝id
                Long userId = follow.getUserId();
                //设置key
                String key = FEED_KEY + userId;
                //保存博客id和当前时间戳到粉丝的redis中
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result blogOfFollow(Long max, Integer offset) {
        //max:上次查询的最小的时间戳
        //offset:偏移量
        //根据传递的max和offset查询redis
        //获取当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = FEED_KEY + userId;
        //由于我们后续还需要获取时间戳，所以需要获得redis中的一条记录 查询redis获取一条记录
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //非空判断
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //计算下一页的时间戳和偏移量
        //获取blog的id列表
        List<Long> ids = new ArrayList<>(typedTuples.size());
        //获取时间戳和偏移量
        long minTime = 0L;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取blog的id列表
            Long id = Long.valueOf(typedTuple.getValue());
            ids.add(id);
            //获取时间戳和偏移量
            long time = typedTuple.getScore().longValue();
            if (minTime == time){
                os++;
            }
            else {
                os = 1;
            }
            minTime = time;
        }
        os = minTime == max? offset + os: os;
        //根据id查询数据库 不能改变id顺序
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list();
        //补充笔记信息
        // 查询用户
        //查询是否点过赞
        blogs.forEach(blog -> {
            getUserByBlog(blog);
            queryIsLikedBlog(blog);
        });
        //返回笔记列表和下一页的参数
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(os);
        return Result.ok(result);
    }

    //如果出现问题，可以取消掉nginx负载均衡，只留下一个接口
}
