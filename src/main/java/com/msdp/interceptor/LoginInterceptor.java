package com.msdp.interceptor;

import com.msdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * <p>
 * 登陆拦截器
 * </p>
 *
 * @author Eason
 * @since 2023-03-15
 */

public class LoginInterceptor implements HandlerInterceptor {



    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
       if(UserHolder.getUser() == null){
           response.setStatus(401);
           return false;
       }
       return HandlerInterceptor.super.preHandle(request, response, handler);
    }
}
