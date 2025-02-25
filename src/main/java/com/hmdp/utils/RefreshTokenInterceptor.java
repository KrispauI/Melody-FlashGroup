package com.hmdp.utils;

import org.springframework.beans.factory.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.hmdp.dto.UserDTO;
import static com.hmdp.utils.RedisConstants.*;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 2. 基于token获取redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                                        .entries(LOGIN_USER_KEY + token);
        // 3. 判断用户是否存在  
        if (userMap.isEmpty()) {
            return true;
        }
        // 5.将查询到的Hash数据转为userDTO对象 
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 6.若存在，保存用户信息至ThreadLocal中
        UserHolder.saveUser(user);
        // 7. 刷新token有效期
        // 每次用户发起请求时，只要携带了有效的token，就会重置该token的过期时间
        // 因此用户只要保持活跃（持续发送请求），token就永远不会过期
        // 实现了自动刷新登录状态的有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        // 8. 放行
        return true;   
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 1. 移除用户信息
        UserHolder.removeUser();
    }
}