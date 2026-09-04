package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@Slf4j
public class RefreshInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //前置拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token
         String token = request.getHeader("authorization");
         if (StrUtil.isBlank(token)){
          return true;
         }
        // 2.根据session中携带的token和redis中的token做比较，找出相同的token
        String keyToken = LOGIN_USER_KEY + token;
        Map<Object,Object> userMap = stringRedisTemplate.opsForHash().entries(keyToken);
        //3.根据token判断用户（value）是否存在
        if (userMap.isEmpty()){
           return true;
        }
        //5.将map用户转换为bean对象
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(userMap,userDTO,false);
        //6.用户存在，保存用户到ThreadLocal，放行
        UserHolder.saveUser(userDTO);
        //7.刷新token的有效期
        stringRedisTemplate.expire(keyToken,LOGIN_USER_TTL, TimeUnit.SECONDS);

        log.info("请求路径 = {}", request.getRequestURI());
        log.info("authorization token = {}", token);
        return true;
    }

    //后置拦截
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
