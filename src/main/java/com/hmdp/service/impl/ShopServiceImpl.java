package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hmdp.utils.RedisConstants.*;

import org.springframework.beans.factory.annotation.*;
import java.util.concurrent.TimeUnit;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3. 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 4. 不存在，查询数据库
        Shop shop = getById(id);    
        if(shop == null){
            // 5.查不到，返回错误
            return Result.fail("店铺不存在");
        }
        // 6. 写入redis 
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. 返回
        return Result.ok(shop);
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
