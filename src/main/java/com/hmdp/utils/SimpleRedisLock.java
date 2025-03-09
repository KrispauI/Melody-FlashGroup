package com.hmdp.utils;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.lang.Thread;



public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    // 构造方法 由业务构造时传参
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    public static final String KEY_PREFIX = "lock:";
    public static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 静态代码块就是类加载的时候会被执行一次，是最好的初始化方法
    // 直接初始化的话，每次加载这个类时都会重新读一次，效率比较低
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
            UNLOCK_SCRIPT,
            Collections.singletonList(KEY_PREFIX + name),
            ID_PREFIX + Thread.currentThread().getId()
        );
    }

    // @Override
    // public void unlock() {
    //     // 获取线程标识
    //     String threadId = ID_PREFIX + Thread.currentThread().getId();
    //     // 获取锁中的线程标识
    //     String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //     // 判断是否一致
    //     if(threadId.equals(id)){
    //         // 释放锁
    //         stringRedisTemplate.delete(KEY_PREFIX + name);
    //     }
    // }
    
}
