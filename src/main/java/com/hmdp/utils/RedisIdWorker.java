package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

//通过redis的自增方法生成全局唯一id
@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //开始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //拼接时由于返回long类型，因此将时间戳左移32位
    private static final long COUNT_BITS = 32;
    public long nextId(String keyPrefix){

        //生成时间戳，当前时间减固定时间
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号,不能将所有业务都用在一个key
        //获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //获取id
        Long count = stringRedisTemplate.opsForValue().increment("icr" + keyPrefix + date);//订单id自增

        //拼接时间戳和日期
        return timeStamp<<COUNT_BITS|count;
    }

}
