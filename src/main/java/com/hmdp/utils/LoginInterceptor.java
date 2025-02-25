package com.hmdp.utils;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        // 2. 放行
        return true;   
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 1. 移除用户信息
        UserHolder.removeUser();
    }
}