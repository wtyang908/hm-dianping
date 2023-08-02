package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_Type_Key;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override//增加缓存
    public Result queryTypeList() {

        //先从redis中查询
        List<String> shopTypes = stringRedisTemplate.opsForList().range(CACHE_SHOP_Type_Key, 0, 9);
        //查询成功返回前端
        List<ShopType> shopTypesForRedis=new ArrayList<>();
        if(shopTypes.size()!=0){
            for( String type : shopTypes){
                ShopType shopTypeForRedis = JSONUtil.toBean(type, ShopType.class);
                shopTypesForRedis.add(shopTypeForRedis);

            }
            return Result.ok(shopTypesForRedis);
        }
        //查询不成功进入数据库查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        //将商铺类型存入redis
        for(ShopType shopType:shopTypeList){
            String shopTypeForRedisFromSQL = JSONUtil.toJsonStr(shopType);
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_Type_Key,shopTypeForRedisFromSQL);
        }
        return Result.ok(shopTypeList);

        //String类型
//        // 1. 从redis中查询商铺类型列表
//        String jsonShopArray = stringRedisTemplate.opsForValue().get(CACHE_SHOP_Type_Key);
//        // json转list
//        List<ShopType> jsonShopList = JSONUtil.toList(jsonShopArray,ShopType.class);
//        // 2. 命中，返回redis中商铺类型信息
//        if (!CollectionUtils.isEmpty(jsonShopList)) {
//            return Result.ok(jsonShopList);
//        }
//        // 3. 未命中，从数据库中查询商铺类型,并根据sort排序
//        List<ShopType> shopTypesByMysql = query().orderByAsc("sort").list();
//        // 4. 将商铺类型存入到redis中
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_Type_Key,JSONUtil.toJsonStr(shopTypesByMysql));
//        // 5. 返回数据库中商铺类型信息
//        return Result.ok(shopTypesByMysql);
    }
}
