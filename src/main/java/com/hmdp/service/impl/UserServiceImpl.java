package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import org.springframework.stereotype.Service;

import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import static com.hmdp.utils.RedisConstants.*;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;

import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;

import org.springframework.beans.factory.annotation.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpSession;

import cn.hutool.core.bean.BeanUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * <p>  
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomString(6);
        // 3. 保存验证码到redis
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 从redis中获取校验验证码
        String cachecode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cachecode == null || !cachecode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        // 3. 查询用户
        User user = query().eq("phone", phone).one();

        // 4. 判断用户是否存在
        if (user == null) {
            //5. 不存在，创建新用户
            user = createUserWithPhone(phone);
        }
        // 6. 保存用户信息到redis
        // 6.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 6.2 将user对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 6.3 存储
        // StringRedisTemplate：使用StringSerializer，所有数据都会被序列化为字符串
        // RedisTemplate：使用JDK序列化，所有数据都会被序列化为字节数组
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 6.4 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        
        return Result.ok(token);
    }
    // 创建用户 
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
