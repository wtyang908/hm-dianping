package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 根据类型分页查询
            // 不需要坐标查询，直接走数据库查询(一般来说，还会按照别的规律查询，但是目前重点不是这里，所以统一为走数据库查询)
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
// 2.计算分页参数
        // 采用 [form,end] 区间的格式，而不是采用 limit 的格式，是因为GEOSEARCHSTORE命令只支持前者，而不支持后者，我猜是这样的
        // from 代表的是从第一个参数开始，并不是从第几页开始！！！
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        // end 代表的是第几个参数结束，和from组成 [from,end]区间
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis，按照距离升序排序、分页。结果：shopId、distance(经度纬度)
        String key = SHOP_GEO_KEY + typeId;
        // GEOSEARCH key FROMLONLAT x y BYRADIUS 5000 m WITHDIST 注意！！！GEOSEARCH命令没有分页功能
        // GeoResults<RedisGeoCommands.GeoLocation<String>> search 对象是一个封装结构的对象
        // 它有一个重要的属性：results，他才是真正的封装查询结果的东西，获取它的方法是getContent()，不是getResults()!!!
        // results是一个List，它的每个元素都是一条记录，有属性：name、point，分别代表 number、经度纬度
        Circle circle=new Circle(x,y, Metrics.KILOMETERS.getMultiplier());
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().includeCoordinates().sortAscending().limit(end);
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo()
                .radius(key,circle,args);
//                        (
//                        key,
//                        GeoReference.fromCoordinate(x, y),
//                        // 不指定单位的话，默认单位为m
//                        new Distance(5000),
//                        // 这个分页是Spring实现的，GEOSEARCH不存在分页功能，所以我们只用使用Spring提供 [0,end]方式的分页
//                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
//                );
        // 4.解析出id
        if (search == null) {
            // results不存在，返回空列表
            return Result.ok(Collections.emptyList());
        }
        // list代表的是List<results>，每个元素都是一条GEO记录
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = search.getContent();
        // 判断 form 的位置是否超过了list中存在的个数
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 [from,end]部分,同时指定大小，不浪费内存空间
        ArrayList<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<String, Distance>(list.size());
        // skip()方法，跳过from位置之前的元素，
        // 因为GEOSEARCH不存在分页功能，所以我们前面使用的是Spring提供 [0,end]方式的分页,然后自己再处理一下，才是 [from,end]的分页数据
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            // 添加到
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离(point对象，距离存储在里面,后面会取出来)
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });

        // 5.根据id去数据库中查询Shop
        String idStr = StrUtil.join(",", ids);
        // 注意mysql中in的排序问题
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            // getValue()获取的就是返回的Point对象中存储的距离
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);

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
