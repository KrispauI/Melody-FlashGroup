package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;

import com.hmdp.utils.*;

import javax.annotation.Resource;

import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
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
    private Redis_IDWorker redis_IDWorker;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        Voucher voucher = voucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock(1200);
        // 判断是否获取成功
        if(!isLock){
            // 获取锁失败，返回错误信息
            return Result.fail("不允许重复抢购");
        }
        try {
            // 获取和事务有关的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock(); 
        }

    }
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 6. 一人一单
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经抢过一次");
        }
        
        // 5. 扣减库存 使用乐观锁防止超卖
        boolean success = voucherService.update()
            .setSql("stock = stock - 1") // set stock = stock - 1
            .eq("voucher_id", voucherId).gt("stock", 0) 
            // where voucher_id = voucherId and stock > 0
            .update();
        if (!success) {
            return Result.fail("库存不足");
        }



        VoucherOrder voucherOrder = new VoucherOrder();

        long orderId = redis_IDWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 设置用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 设置优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // voucherOrder.setPayState(OrderState.UNPAID);
        // voucherOrder.setCreateTime(LocalDateTime.now());
        // voucherOrder.setPayTime(null);
        // voucherOrder.setUseTime(null);
        return Result.ok(orderId);
    }
    
}
