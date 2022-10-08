package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author qjl
 * @create 2022-10-07 11:12
 */
public class SimpleRedisLock implements ILock{
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    //尝试获取锁
    @Override
    public boolean tryLock(long timeoutSec) {
        //执行set操作 SET lock thread1 NX EX 10,key使用前缀加name，value使用线程id，修改锁的id，使用线程UUID+id
        //取线程id,是lang类型，加上”“变成字符串
        String id =ID_PREFIX+ Thread.currentThread().getId();
//        stringRedisTemplate.opsForValue().set(KEY_PREFIX+name+"dd", id);
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, id, timeoutSec, TimeUnit.SECONDS);
        //使用自动拆箱
        return Boolean.TRUE.equals(success);
    }

    //解锁的操作，直接删除即可

    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+ Thread.currentThread().getId());
    }
//    @Override
//    public void unlock() {
//        //获取锁的id与线程id是否一致，一致可以删除
//        String threadid =ID_PREFIX+ Thread.currentThread().getId();
//        String lockid = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if(threadid.equals(lockid)){
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//
//    }
}
