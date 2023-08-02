package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 注意！！！其实也可以通过yaml的方式配置Redisson客户端，因为Redisson提供了SpringBoot的start启动器，听起来这样很方便，但是yaml配置方式会替代Spring官方对redis的配置和实现，建议不要使用这种方式。
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redisClient(){
        // 配置类
        Config config = new Config();
        // 添加redis地址，这里添加了单点的地址(redis集群可以用useClusterServers())，也可以使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.204.128:6379");
        // 创建连接客户端
        return Redisson.create(config);
    }
}
