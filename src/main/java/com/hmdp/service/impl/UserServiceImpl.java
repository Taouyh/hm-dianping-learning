package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result sendCode(String phone) {
       //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号不合法，请重新输入");
        }
        //2.随机生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码到redis中  key: phone  value : 验证码
        String key = LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(key,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.debug("验证码 {}",code);
        return Result.ok();
    }

    @Override
    public Result sign() {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return Result.fail("当前用户未登录");
        }
        //2.获取当前时间
        LocalDateTime now = LocalDateTime.now();
        //3.key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + user.getId() + keySuffix;
        //4.保存到redis
        stringRedisTemplate.opsForValue().setBit(key, now.getDayOfMonth() - 1, true);
        //5.返回
        return Result.ok();
    }

    //连续签到统计
    @Override
    public Result signCount() {
        //获取用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();
        //获取时间
        LocalDateTime now = LocalDateTime.now();
        //获取今天是一个月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //查询
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        //非空判断
        if (result == null || result.isEmpty()){
            return Result.ok(0);
        }
        //根据查询结果换算
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);

        //注意：
        /*也就是虽然得到的是十进制数，但进行与运算和逻辑移位时，依旧会自动以二进制表示并进行运算*/

    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //1.验证手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号无效,请重新输入");
        }

        //2.验证验证码是否正确
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !code.equals(cacheCode)) {
            return Result.fail("验证码无效");
        }

        //3.根据手机号查找用户的一条记录
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //将用户信息保存到redis中作为value， 随机生成token作为key
        String token = UUID.randomUUID().toString();
        //用户信息使用hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        HashMap<String,Object> userMap = (HashMap<String, Object>) BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fileName,fileValue)-> fileValue.toString()));
        String keyToken = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(keyToken,userMap);
        //设置token的有效时间
        stringRedisTemplate.expire(keyToken,LOGIN_USER_TTL,TimeUnit.SECONDS);
        //返回token给客户端
        log.info("login token = {}", token);
        return Result.ok(token);
    }



    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX
                + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
