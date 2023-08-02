package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
       //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return  Result.ok(shop);


    }

    @Override
    public Result updateShop(Shop shop) {
        //判断shop是否存在
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //先更新数据库
        updateById(shop);
        //删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY +id);
        return Result.ok();

    }
    //通过stringRedisTemplate中的方法来获取类似互斥的锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, Lock_SHOP_VALUE, LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //缓存穿透代码
    public Shop queryWithPassThrough(Long id){
        //从redis中查询信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //判断是否存在,isBlank为null或者为“”都为true
        if(StrUtil.isNotBlank(shopJson)){
            //存在手动反序列化并返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return  shop;
        }
        //为空还需要判断是否为空值，解决缓存穿透问题,空值返回false
        if(shopJson!=null){
            return null;
        }
        //不存在在数据库中查询
        Shop shop = getById(id);
        //查询结果为空
        if(shop==null){
            //解决缓存穿透的问题，将null值返回到redis中,向redis中存储时使用空字符串
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_SHOP_TTL, TimeUnit.SECONDS);
            return  null;
        }
        //存在则存入redis,并增加过期时间
        //解决1缓存雪崩，在ttl后增加随机数
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL+ RandomUtil.randomLong(50), TimeUnit.MINUTES);
        //返回前端
        return shop;
    }

    //缓存击穿代码，互斥锁的方法
    public Shop queryWithMutex(Long id){
        //从redis中查询信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //判断是否存在,isBlank为null或者为“”都为true
        if(StrUtil.isNotBlank(shopJson)){
            //存在手动反序列化并返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return  shop;
        }
        //为空还需要判断是否为空值，解决缓存穿透问题,空值返回false
        if(shopJson!=null){
            return null;
        }
        Shop shop=null;
        //***实现缓存重建
        try {
            //*1获取互斥锁
            boolean lock = tryLock(LOCK_SHOP_KEY + id);

            //*2判断获取锁是否成功
            if(!lock){
                //*3 获取失败休眠
                Thread.sleep(50);
                return queryWithMutex(id);//不确定是否完成，递归
            }
            //*4获取互斥锁成功，在数据库中查询
            shop = getById(id);
            //查询结果为空
            if(shop==null){
                //解决缓存穿透的问题，将null值返回到redis中,向redis中存储时使用空字符串
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_SHOP_TTL, TimeUnit.SECONDS);
                return  null;
            }
            //*5存在则存入redis,并增加过期时间
            //解决缓存雪崩，在ttl后增加随机数
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL+ RandomUtil.randomLong(50), TimeUnit.MINUTES);

        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //*6释放互斥锁
            unLock(LOCK_SHOP_KEY + id);
            //返回前端

        }
        return shop;
    }

    //保存增加了逻辑过期时间的shop，用于逻辑过期解决缓存击穿
    public void saveShopToRedis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire( Long id ) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            //成功开启独立线程
            CACHE_REBUILD_EXECUTOR.submit( ()->{
                try{
                    //重建缓存
                    this.saveShopToRedis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
}
