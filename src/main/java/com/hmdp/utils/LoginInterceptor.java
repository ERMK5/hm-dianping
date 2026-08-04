package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// 登录拦截器，拦截部分请求（不拦截登录页面以及用户未登录也允许查看的页面）
/*
检查 ThreadLocal 是否存在用户；
不存在 → 返回 401 拦截；存在则放行。
*/
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否需要拦截（ThreadLocal当中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有用户，即未登录需要拦截，设置状态码
            response.setStatus(401);
            return false;
        }
        // 有用户，放行
        return true;
    }
}
