package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.cglib.core.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final CacheClient cacheClient;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate, CacheClient cacheClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheClient = cacheClient;
    }

    @Override
    public Result queryById(Long id) {

        //解决缓存穿透问题
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        //利用互斥锁解决缓存击穿问题
        //Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
        //利用逻辑过期解决缓存击穿问题
        //Shop shop2 = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
    }

    /**
     * 缓存空对象解决缓存穿透问题
     * @param id
     * @return
     */
    public Shop queryWithPutThrough(Long id) {
        // 1.前端发起请求提交商铺id
        String key = CACHE_SHOP_KEY+id;
        // 2.查询redis中数据
        String value = stringRedisTemplate.opsForValue().get(key);
        // 3.有相关数据，则直接返回
        if (StrUtil.isNotBlank(value)){
            //将JSON转为bean对象返回
            Shop shop = JSONUtil.toBean(value, Shop.class);
            return shop;
        }
        if (value != null){
            return null;
        }
        // 4.没有则去数据库查询
        Shop shop = getById(id);
        // 5.数据库中查询到了，写入redis，返回
        if (shop != null){
            //将shop转为JSON对象
            String shopJSON = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key,shopJSON,CACHE_SHOP_TTL, TimeUnit.MINUTES);//为解决缓存与数据库数据一致问题，设计一个TTL
            return shop;
        }
        // 6.数据库中也没有，返回404
        // 为解决缓存穿透问题，这里数据库中不存在的话，缓存空值
        stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

        return null;
    }

    /**
     * 利用互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        // 1. 拼接店铺缓存 Key
        String key = CACHE_SHOP_KEY + id;

        // 2. 查询 Redis
        String value = stringRedisTemplate.opsForValue().get(key);

        // 3. 命中正常店铺缓存
        if (StrUtil.isNotBlank(value)) {
            return JSONUtil.toBean(value, Shop.class);
        }

        // 4. 命中空值缓存，说明店铺不存在
        if (value != null) {
            return null;
        }

        // 5. 缓存未命中，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);

        // 6. 获取锁失败，等待后重试
        if (!lock) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待互斥锁时线程被中断", e);
            }

            return queryWithMutex(id);
        }

        try {
            // 7. 获取锁成功后，再次查询 Redis，进行 Double Check
            value = stringRedisTemplate.opsForValue().get(key);

            // 7.1 其他线程已经写入正常店铺缓存
            if (StrUtil.isNotBlank(value)) {
                return JSONUtil.toBean(value, Shop.class);
            }

            // 7.2 其他线程已经写入空值缓存
            if (value != null) {
                return null;
            }

            // 8. 查询数据库
            Shop shop = getById(id);

            // 9. 数据库中不存在，缓存空字符串
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(
                        key,
                        "",
                        CACHE_NULL_TTL,
                        TimeUnit.MINUTES
                );
                return null;
            }

            // 10. 数据库中存在，写入 Redis
            String shopJSON = JSONUtil.toJsonStr(shop);

            stringRedisTemplate.opsForValue().set(
                    key,
                    shopJSON,
                    CACHE_SHOP_TTL,
                    TimeUnit.MINUTES
            );

            return shop;
        } finally {
            // 11. 只有获取锁成功的线程才会执行到这里
            unlock(lockKey);
        }
    }


    /**
     * 利用逻辑过期解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        // 1. 拼接店铺缓存 Key
        String key = CACHE_SHOP_KEY + id;
        // 2. 查询 Redis
        String value = stringRedisTemplate.opsForValue().get(key);
        // 3. 未命中正常店铺缓存
        if (StrUtil.isBlank(value)) {
            return null;
        }
        // 4. 缓存命中，尝试获取互斥锁
        // TODO 判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(value, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //获取店铺信息
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        // TODO 缓存未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        // TODO 缓存过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        // TODO 6. 获取锁失败，直接返回旧数据
        if (!lock) {
            return shop;
        }

        //判断异步线程是否获得了异步锁
        boolean lockTransferred = false;

        try {
            String checkedResult = stringRedisTemplate.opsForValue().get(key);
            //DOUBLE CHECK
            if (StrUtil.isNotBlank(checkedResult)) {
                RedisData checkedData =
                        JSONUtil.toBean(checkedResult, RedisData.class);

                JSONObject checkedShopData =
                        (JSONObject) checkedData.getData();

                if (checkedData.getExpireTime().isAfter(LocalDateTime.now())) {
                    return JSONUtil.toBean(checkedShopData, Shop.class);
                }
            }

            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopRedis(id, 10L);
                } finally {
                    unlock(lockKey);
                }
            });

            lockTransferred = true;
        } finally {
            if (!lockTransferred) {
                unlock(lockKey);
            }
        }
        return shop;
    }

    @Override
    public Result update(Shop shop) {
        // 写入数据库
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        // TODO 先更新数据库
        updateById(shop);
        // TODO 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    //尝试获取互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //重建redis缓存(要先通过单元缓存进行预热)
    public void saveShopRedis(Long id,Long exprireSeconds){
        //获取数据库对象
        Shop shop = getById(id);
        //设置逻辑过期时间
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(exprireSeconds);
        //封装RedisData对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(expireTime);
        //重建redis缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

}
