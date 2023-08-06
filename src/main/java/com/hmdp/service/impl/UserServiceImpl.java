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
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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

    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime time = LocalDateTime.now();
        //拼接key
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY +userId+keySuffix;
        //获取当前日期
        int dayOfMonth = time.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();

    }

    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime time = LocalDateTime.now();
        //拼接key
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY +userId+keySuffix;
        //获取当前日期
        int dayOfMonth = time.getDayOfMonth();
        //获取本月截至今天的签到记录，返回一个十进制数据
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(result==null)
            return Result.ok(0);
        Long num = result.get(0);
        if(num==null||num==0)
            return Result.ok(0);
        int count=0;
        //循环遍历
        while (true){
            //数字与1做与运算,判断结果是否为0
            if((num&1)==0){
                //未签到，结束
                break;
            }else {
                //加1
                count++;
            }
            //右移num 1位
            num>>>=1;
        }
        return Result.ok(count);
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
