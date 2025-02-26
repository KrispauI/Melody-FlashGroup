package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import static com.hmdp.utils.RedisConstants.*;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.StrUtil;

import java.util.List;

import javax.annotation.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1. 从redis中查询商铺缓存
        String key = CACHE_SHOP_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 反序列化为List<ShopType>
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        // 2. 不存在，查询数据库
        List<ShopType> list = query().orderByAsc("sort").list();
        // 3. 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(list));
        // 4. 返回
        return Result.ok(list);
    }
}
