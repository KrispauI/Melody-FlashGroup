package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hmdp.utils.RedisConstants.*;

import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;

import org.springframework.beans.factory.annotation.*;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * <p>  
 *  服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 使用缓存工具类解决缓存击穿问题
        // Shop shop = queryWithPassThrough(id);
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
       
        // // 互斥锁解决缓存击穿问题
        // Shop shop = queryWithLogical(id);

        // 逻辑过期解决缓存击穿问题
        // Shop shop = queryWithLogical(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        // 7. 返回
        return Result.ok(shop);
    }

    // 使用缓存空对象 解决缓存穿透
    public Shop queryWithPassThrough(Long id){
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2.判断是否存在
        // 命中有效缓存
        if(StrUtil.isNotBlank(shopJson)){
            // 3. 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 命中空缓存 (key存在但值为空字符串时)
        // shopJson.equals("") 会抛出NullPointerException
        if(shopJson != null){
            return null;
        }
        // 4. 不存在，查询数据库
        Shop shop = getById(id);   
        // 5.查不到，将空值写入redis
        if(shop == null){
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_SHOP_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6. 写入redis 
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
// 方法1: 互斥锁解决缓存击穿问题
    // public Shop queryWithMutex(Long id) {
    //     // 1.从redis中查询商铺缓存
    //     String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //     // 2.判断是否存在
    //     // 命中有效缓存
    //     if(StrUtil.isNotBlank(shopJson)){
    //         // 3. 存在，直接返回
    //         Shop shop = JSONUtil.toBean(shopJson, Shop.class);
    //         return shop;
    //     }
    //     // 命中空缓存 (key存在但值为空字符串时)
    //     // shopJson.equals("") 会抛出NullPointerException
    //     if(shopJson != null){
    //         return null;
    //     }
    //     // 4. 针对缓存击穿实现缓存重建
    //     // 4.1 获取互斥锁
    //     String lock_key = LOCK_SHOP_KEY + id;
    //     Shop shop = null;
    //     boolean isLock = false;
    //     try {
    //         // 4.2 判断是否获取成功
    //         // 循环获取锁 避免递归
    //         while (!(isLock = tryLock(lock_key))) {
    //             Thread.sleep(50);
    //         }
    //         // 4.3 获取成功后双重检查缓存，再次查询redis缓存，查询数据库
    //         shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //         if(StrUtil.isNotBlank(shopJson)){
    //             shop = JSONUtil.toBean(shopJson, Shop.class);
    //             return shop;
    //         }
    //         // 4.4 查不到，将空值写入redis
    //         shop = getById(id);   
    //         if(shop == null){
    //             stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_SHOP_NULL_TTL, TimeUnit.MINUTES);
    //             // 返回错误信息
    //             return null;
    //         }
    //         // 6. 写入redis 
    //         stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //     }catch (InterruptedException e) {
    //         throw new RuntimeException(e);
    //     }finally{
    //         // 7. 释放互斥锁
    //         unlock(lock_key);
    //     }
    //     // 8. 返回
    //     return shop;
    // }

    private boolean tryLock(String key){
        // 返回的是Boolean对象（包装类），而不是boolean基本类型
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY + key, "1", 10, TimeUnit.SECONDS);
        // 直接返回时，当flag为null时，自动拆箱会抛出空指针异常
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(LOCK_SHOP_KEY + key);
    }
    // 定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 方法2： 使用逻辑过期策略解决缓存击穿问题 并整合了缓存空对象解决缓存穿透的解决方案
    public Shop queryWithLogical(Long id){
        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 判断是否存在
        // StrUtil.isBlank(json) 其实是两种情况 json为null 或 json为空字符串
        if(shopJson == null){
            // 3. 在查询数据库时 将查不到的数据载入空缓存 防止缓存穿透
            // 初始状态下Redis没有缓存，不会触发空指针异常
            // 理论上需要提前进行缓存预热
            return handleDbQuery(id);
        }
        // 4. 命中空缓存的处理
        if ("".equals(shopJson)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);

        // 5. 命中，继续反序列化
        // 将redisData中的data转换为Shop对象
        Shop shop = JSONUtil.toBean((JSONObject)redisData.getData(), Shop.class);
        // 6. 判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        // 6.1 未过期，直接返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 7 过期，重建缓存
        // 7.1 获取互斥锁
        String lock_key = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lock_key);
        // 7.2 判断是否获取成功
        if(isLock){
            // 获取锁成功后，双重检查缓存是否过期
            // 双重检查应放在提交异步任务前
            // 减少不必要的线程任务创建  更快地释放锁，提高系统吞吐量
            String New_shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            RedisData New_redisData = JSONUtil.toBean(New_shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject)New_redisData.getData(), Shop.class);
            
            // 如果缓存未过期，则无需重建
            if(New_redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                unlock(lock_key);
                return shop;
            }
            
            // 确认过期后，才提交异步任务重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally{
                    // 释放互斥锁
                    unlock(lock_key);
                }
            });
        }
        // 8. 返回
        return shop;
    }
    // 数据库查询兜底方法
    private Shop handleDbQuery(Long id) {
        Shop shop = getById(id);
        if (shop == null) {
            // 防止缓存穿透
            stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_KEY + id, 
                "", 
                CACHE_NULL_TTL, 
                 TimeUnit.MINUTES
            );
            return null;
        }
        // 写入缓存
        saveShopToRedis(id, 20L);
        return shop;
    }
    public void saveShopToRedis(Long id, Long expireTime){
        // 1. 查询数据库
        Shop shop = getById(id);
        // Thread.sleep(2000);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        // 3. 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除redis中的缓存
        // stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 使用事务同步管理器实现延迟删除缓存
        // 原方案中，缓存删除是在事务内部执行的，事务此时尚未提交。
        // 从"执行缓存删除操作"到"事务提交完成"的过程中
        // 其他线程可能因为缓存缺失而查询到旧的数据库数据并重建缓存
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // 使用Lambda表达式注册事务同步器
            @Override
            public void afterCommit() {
                stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
            }
        }); 
        return Result.ok(shop);
    }
}
