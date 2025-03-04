package com.hmdp.utils;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    // 根据指定的key查询缓存，并反序列化为指定类型
    // 不能写死 以提高复用性 所以需要使用泛型来处理不同数据类型
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, 
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) 
    {
        String key = keyPrefix + id;
        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 4. 不存在，调用数据库查询
        R r = dbFallback.apply(id);
        // 5. 不存在，返回错误
        if (r == null) {
            return null;
        }
        // 6. 存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 封装逻辑过期的缓存查询的工具类
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, 
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) 
    {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if(StrUtil.isBlank(json)){
            R temp = dbFallback.apply(id);
            if (temp != null) {
                // 重建缓存
                setWithLogicalExpire(key, temp, time, unit);
            }
            return temp;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 4. 增加非空检查
        // 初始状态下Redis没有缓存，不会触发空指针异常
        // 理论上需要提前进行缓存预热
        // if(redisData == null || redisData.getData() == null){
        //     return null;
        // }  
        // 5. 命中，继续反序列化
        // 将redisData中的data转换为Shop对象
        R r = JSONUtil.toBean((JSONObject)redisData.getData(), type);
        // 6. 判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        // 6.1 未过期，直接返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
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
            String New_shopJson = stringRedisTemplate.opsForValue().get(key);
            RedisData New_redisData = JSONUtil.toBean(New_shopJson, RedisData.class);
            r = JSONUtil.toBean((JSONObject)New_redisData.getData(), type);
            // 如果缓存未过期，则无需重建
            if(New_redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                unlock(lock_key);
                return r;
            }
            // 确认过期后，才提交异步任务重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    R r1 = dbFallback.apply(id);
                    setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally{
                    // 释放互斥锁
                    unlock(lock_key);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key){
        // 返回的是Boolean对象（包装类），而不是boolean基本类型
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY + key, "1", 10, TimeUnit.SECONDS);
        // 直接返回时，当flag为null时，自动拆箱会抛出空指针异常
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(LOCK_SHOP_KEY + key);
    }
}
