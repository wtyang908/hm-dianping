package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor{//为了防止用户不访问下一个拦截器的页面导致token超过30s失效
    //自己构建的无法被注入，但加了@Configuration可以有spring注入
    private StringRedisTemplate stringRedisTemplate;
    //通过MvcConfig来注入
    public RefreshTokenInterceptor (StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {



        //获取请求头的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        //根据token从redis取出map并转化为user
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);//看下存进去redis的分别是什么
        if(userMap.isEmpty()){
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //存在时保存到ThreadLocal
        UserHolder.saveUser(userDTO);
        //刷新token有效期，防止30分钟直接过期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除user
        UserHolder.removeUser();
    }
}
