package com.cama.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


import static com.cama.utils.RedisConstants.*;

/**
 * @author qjl
 * @create 2022-10-06 9:12
 * 需要实现四个方法：
 * 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
 * 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
 * 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
 * 方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
 */
@Component
@Slf4j
public class CacheClient {
    @Autowired
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //方法一：将任意的java对象序列化为String类型并设置过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        //将对象转化为JSON对象进行存储
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //方法二：将任意的java对象序列化为String类型并设置逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //将对象转化为JSON对象进行存储
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //方法三：利用缓存空值的方法解决缓存穿透的问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                         Function<ID,R> dbFallback,
                                         Long time, TimeUnit unit){
//        先进行查找缓存
        String id_c = keyPrefix + id;
//        通过id进行查找
        String json = stringRedisTemplate.opsForValue().get(id_c);
//        判断shop是否查找到，查到直接返回
        if (StrUtil.isNotBlank(json)) {
//        查到将字符串进行反序列化，生成一个对象返回
            return JSONUtil.toBean(json,type);
        }
//        当查找的是”“则不可以继续进行数据库的查找，防止缓存穿透
        if(json != null) {
            return null;
        }
//        没查到进行数据库的查找
        R r = dbFallback.apply(id);
        if(r == null) {
//        将空值返回redis.避免缓存穿透
            stringRedisTemplate.opsForValue().set(id_c,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
//        数据库存在，缓存不存在，将该数据缓存到redis中,注意此时该对象不可以直接存储到redis中，需要转换为json格式,设置时间
        this.set(id_c,r,time,unit);
        return r;
    }

//    方法四：利用逻辑过期的方法解决缓存击穿的问题
    public <R,ID> R queryWithLogicExpire(String keyPrefix, ID id,Class<R> type, Function<ID ,R> dbFallback,Long time,TimeUnit unit){
//        先进行查找缓存
        String id_c = keyPrefix + id;
//        通过id进行查找
        String json = stringRedisTemplate.opsForValue().get(id_c);
//        判断是否存在，不存在就返回空值
        if (StrUtil.isBlank(json)) {
            return null;
        }
//        命中需要查看过期时间
//        反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断过期时间是否
        // 未过期
        if(expireTime.isAfter(LocalDateTime.now())){
//            未过期直接返回
            return r;
        }
//        已经过期：缓存重建
//        获取锁
        String lockKey = LOCK_SHOP_KEY+ id;
        boolean b = tryLock(lockKey);
        if (b){

            //成功就开启独立的线程，使用线程池
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallback.apply( id);
                    //写入数数据库里加入逻辑过期时间
                    this.setWithLogicalExpire(id_c,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
//                释放锁
                    unlock(lockKey);

                }
            });


        }
        //不管是否成功获取锁都会进行返回商铺的信息
        return r;

    }

    //定义加锁的操作   setIfAbsent相当于setnx，表示当key存在时返回false，key不存在时进行操作
    private boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        log.info("aBoolean"+aBoolean);
        return BooleanUtil.isTrue(aBoolean);

    }
    //定义删除锁即解锁的操作
    private void  unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
