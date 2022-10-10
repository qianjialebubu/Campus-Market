package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author qjl
 * @create 2022-10-08 19:14
 */
@Configuration
public class RedissionConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.200.129:6379");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
    @Bean
    public RedissonClient redissonClient2(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.200.129:6380");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
    @Bean
    public RedissonClient redissonClient3(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.200.129:6381");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
