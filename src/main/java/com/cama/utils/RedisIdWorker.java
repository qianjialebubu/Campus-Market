package com.cama.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author qjl
 * @create 2022-10-06 12:21
 */
@Component
public class RedisIdWorker {

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //开始的时间戳，开始的时间戳是(2022, 1, 1, 0, 0, 0)的
    private static final Long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;//序列号32位
    public Long nextId(String keyPrefix){
        //1、生成时间戳,用当前的时间戳减去开始的时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = second - BEGIN_TIMESTAMP;
        //2、生成序列号
        //2.1 以日期精确到天为单位，作为key的一部分，便于日后的统计
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 设置自增长,生成序列号,相当于每一天进行重新编号
        Long count = stringRedisTemplate.opsForValue().increment("inc" + keyPrefix + ":" + data);
        //3、拼接返回生成订单id
        return timestamp<<COUNT_BITS|count;
    }


    public static void main(String[] args) {
        //测试生成的时间戳
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        LocalDateTime time_now = LocalDateTime.now();
        long second_now = time_now.toEpochSecond(ZoneOffset.UTC);
        String data = time_now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss "));
        System.out.println("second："+second );
        System.out.println("second："+second_now );
        System.out.println(data);




    }
}
