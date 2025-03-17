package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;

import com.hmdp.utils.*;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hmdp.service.IVoucherService;

import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private Redis_IDWorker redis_IDWorker;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 静态代码块就是类加载的时候会被执行一次，是最好的初始化方法
    // 直接初始化的话，每次加载这个类时都会重新读一次，效率比较低
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    // 从队列中获取订单任务
                    // 从阻塞队列中获取订单，如果队列为空则阻塞等待
                    VoucherOrder voucherorder = orderTasks.take();
                    // 处理订单逻辑
                    handleVoucherOrder(voucherorder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    break;
                }
            }
        }
    }

    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     // 1. 查询优惠券
    //     Voucher voucher = voucherService.getById(voucherId);
    //     // 2. 判断秒杀是否开始
    //     if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
    //         return Result.fail("秒杀尚未开始");
    //     }
    //     // 3. 判断秒杀是否结束
    //     if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
    //         return Result.fail("秒杀已经结束");
    //     }
    //     // 4. 判断库存是否充足
    //     if (voucher.getStock() < 1) {
    //         return Result.fail("库存不足");
    //     }
    //     Long userId = UserHolder.getUser().getId();
    //     // 创建锁对象
    //     // 创建锁对象 原本使用lua脚本，后改为redisson
    //     // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
    //     RLock lock = redissonClient.getLock("lock:order:" + userId);
    //     // 尝试获取锁
    //     // boolean isLock = lock.tryLock(1200);
    //     boolean isLock = lock.tryLock();
    //     // 判断是否获取成功
    //     if(!isLock){
    //         // 获取锁失败，返回错误信息
    //         return Result.fail("不允许重复抢购");
    //     }
    //     try {
    //         // 获取和事务有关的代理对象
    //         IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //         return proxy.createVoucherOrder(voucherId);
    //     } finally {
    //         // 释放锁
    //         lock.unlock(); 
    //     }
    // }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本 判断用户有没有购买资格
        Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString() + userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        
        if(r != 0){
            // 2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" :"不能重复下单");
        }

        // 2.2 为0，即有资格，把下单信息保存至阻塞队列
        long orderId = redis_IDWorker.nextId("order");
        // 保存阻塞队列

        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        voucherOrder.setId(orderId);
        // 设置用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 设置优惠券id
        voucherOrder.setVoucherId(voucherId);

        // 优惠券时间相关的信息由前端做校验 未开始的优惠券在前端不会显示

        // 放入阻塞队列进行异步下单
        orderTasks.add(voucherOrder);
        // 在父线程中获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }
    // 异步处理订单的业务逻辑
    private void handleVoucherOrder(VoucherOrder voucherorder){
        Long userId = voucherorder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        // boolean isLock = lock.tryLock(1200);
        boolean isLock = lock.tryLock();
        // 判断是否获取成功
        if(!isLock){
            // 获取锁失败，返回错误信息
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取和事务有关的代理对象
            proxy.createVoucherOrder(voucherorder);
        } finally {
            // 释放锁
            lock.unlock(); 
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherorder) {
        // 6. 一人一单
        Long userId = voucherorder.getUserId();
        // 获取优惠券id
        Long voucherId = voucherorder.getVoucherId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经抢过一次");return;
        }
        // 5. 扣减库存 使用乐观锁防止超卖
        boolean success = voucherService.update()
            .setSql("stock = stock - 1") // set stock = stock - 1
            .eq("voucher_id", voucherId).gt("stock", 0) 
            // where voucher_id = voucherId and stock > 0
            .update();
        if (!success) {
            log.error("库存不足");return;
        }
        save(voucherorder);
    }   
}
