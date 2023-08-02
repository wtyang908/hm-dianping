package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.KEY_PREFIX;

public class SimpleRedisLock implements ILock{

    //id前缀
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    //提前加载Lua文件
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }



    private StringRedisTemplate stringRedisTemplate;

    private String name;

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String key=KEY_PREFIX+name;
        //分布式锁最好加上当前线程的值作为value,释放锁时使用，避免删掉不属于自己的锁，但这个线程id是jvm配置的递增的，
        // 因此不同的jvm可能会造成冲突，在前面增加一个uuid来确保唯一

        String value = ID_PREFIX+Thread.currentThread().getId();
        Boolean res = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        //避免空指针风险
        return Boolean.TRUE.equals(res);
    }

    //由于释放锁时有可能出现阻塞，因此需要将判断与删除一起变为原子性操作,使用Lua脚本

    @Override
    public void unLock() {//调用Lua脚本
        Long execute = stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }


//    @Override
//    public void unLock() {
//        String key=KEY_PREFIX+name;
//        //获取线程标识
//        String value = ID_PREFIX+Thread.currentThread().getId();
//        //获取锁中的标识
//        String lockValue = stringRedisTemplate.opsForValue().get(key);
//        //如果线程标识与锁的标识一致
//        if (value.equals(lockValue)) {
//            stringRedisTemplate.delete(key);
//        }
//    }
}
