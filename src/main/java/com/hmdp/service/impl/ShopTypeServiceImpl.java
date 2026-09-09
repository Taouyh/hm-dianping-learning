package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public List<ShopType> queryList() {
        // 1.redis中查询店铺类型信息
        String shopTypeJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOPTYPE_KEY);
        List<ShopType> list = new ArrayList<>();
        //2.redis中存在，返回list
        if (shopTypeJSON != null){
            list = (List<ShopType>) JSONUtil.toList(shopTypeJSON, ShopType.class);
            return list;
        }

        //3.redis中不存在，查询数据库
        list = query().orderByAsc("sort").list();
        //6.不存在，返回null
        if (list == null || list.isEmpty()){
            return null;
        }
        //4.将数据库信息写回redis
        shopTypeJSON = JSONUtil.toJsonStr(list);
        stringRedisTemplate.opsForValue().set(CACHE_SHOPTYPE_KEY,shopTypeJSON);
        //5.返回店铺类型列表
        return list;
    }
}
