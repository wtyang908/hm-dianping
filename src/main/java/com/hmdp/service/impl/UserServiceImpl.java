package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
//    @Override  这是session代码,验证码
//    public Result sendCode(String phone, HttpSession session) {
//        //判断电话号是否符合规范
//        if(RegexUtils.isPhoneInvalid(phone)){
//            return Result.fail("手机号格式错误");
//        }
//        //生成随机验证码
//        String code = RandomUtil.randomNumbers(6);
//        //存入session
//        session.setAttribute("code",code);
//        //发送验证码，这个之后再做,先用日志代替
//        log.debug("验证码发送成功，验证码:"+code);
//        return Result.ok();}

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //判断电话号是否符合规范
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //生成随机验证码
        String code = RandomUtil.randomNumbers(6);
        //存入redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码，这个之后再做,先用日志代替
        log.debug("验证码发送成功，验证码:"+code);
        return Result.ok();
    }

//    @Override  session登录
//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        //判断电话号是否符合规范,这与上面的并不是同一个请求，因此需要在校验一次
//        String phone = loginForm.getPhone();
//        if(RegexUtils.isPhoneInvalid(phone)){
//            return Result.fail("手机号格式错误");
//        }
//        //检验校验码
//        Object cashCode = session.getAttribute("code");
//        System.out.println(cashCode);
//        String code=loginForm.getCode();
//        if(code ==null||!cashCode.toString().equals(code)){
//            return Result.fail("验证码错误");
//        }
//        //验证通过,根据手机号查询用户
//        User user = query().eq("phone", phone).one();
//        //判断用户是否存在
//        //不存在创建用户并保存
//        if(user==null){
//            user=createUserWithPhone(phone);
//        }
//        //将信息存入session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
//        return Result.ok();
//    }
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //判断电话号是否符合规范,这与上面的并不是同一个请求，因此需要在校验一次
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //从redis中获取校验码
        String cashCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code=loginForm.getCode();
        if(cashCode ==null||!cashCode.equals(code)){
            log.debug("redis验证码"+cashCode+"填入的"+code);
            return Result.fail("验证码错误");
        }
        //验证通过,根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //判断用户是否存在
        //不存在创建用户并保存
        if(user==null){
            user=createUserWithPhone(phone);
        }
        //生成随机TOKEN
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //user转化为Map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));//CopyOptions自定义将long型id转为string
        //usrDto对象使用Hash存储在redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        //设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.SECONDS);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(8));
        save(user);
        return user;

    }
}
