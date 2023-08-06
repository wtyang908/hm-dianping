package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.config.RabbitMQConfig.EXCHANGE_NAME;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate  stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Test
    void testSaveShop(){
        shopService.saveShopToRedis(1l,1l);
    }

    @Test
    void loadShopData(){
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            // 创建该对象，用于批量插入
            ArrayList<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.2.写入redis GEOADD key 经度 维度 member
            for (Shop shop : value) {
                // 这个方法是每一条数据添加一次，性能损耗大，建议后面的批量方法
                // stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY(),shop.getId().toString())
                // 先添加到这个List中，后面一次性批量插入
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            // 批量插入locations里的数据
            // 3.3.写入redis GEOADD key 经度 维度 member
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

    @Test
    public void testSend(){
        rabbitTemplate.convertAndSend(EXCHANGE_NAME,"boot.first","first mq---------------------------");
    }

}
