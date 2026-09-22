package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
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
    //注入stringRedisTemplate和redisLock实现分布式锁
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.查询开始时间
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //3.查询结束时间
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4.查询库存
        Integer stock = seckillVoucher.getStock();
        //5.库存不足，返回异常
        if (stock < 1){
            return Result.fail("库存不足");
        }

        //获取当前用户的id
        Long userId = UserHolder.getUser().getId();
        //不再利用悲观锁了，利用分布式锁
        //创建锁
        SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        //获取锁
        boolean success = lock.tryLock(1200);
        //获取锁失败
        if (!success){
            return Result.fail("一个用户只允许下一单");
        }
        //执行业务
        try{
            return createVocherOrder(voucherId);
        }finally {
            //释放锁
            lock.unLock();
        }
    }

    @Transactional
    public Result createVocherOrder(Long voucherId) {
        //TODO 通过查询订单 解决一人一单的问题，但依旧存在并发安全问题
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        //6.更新库存
        //应用乐观锁CAS解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        if (!success){
            return Result.fail("库存不足");
        }
        //7.库存充足,创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2优惠券id
        voucherOrder.setVoucherId(voucherId);
        //7.3用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        save(voucherOrder);
        //8.返回订单id
        return Result.ok(orderId);
    }
}
