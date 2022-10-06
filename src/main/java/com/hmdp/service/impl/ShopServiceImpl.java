package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author qjl
 * @since 2022.10.6
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    //@Autowired
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
            //缓存穿透
//        return Result.ok(queryWithPassThrough(id));
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //使用逻辑过期时间解决缓存击穿的问题
        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //使用互斥锁解决缓存击穿的问题
//        Shop shop = queryWithmutex(id);
//        Shop shop = queryWithLogicExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 使用逻辑过期解决缓存穿透问题
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id){
        // 先进行查找缓存
        String id_c = CACHE_SHOP_KEY + id;
//        通过id进行查找
        String shop = stringRedisTemplate.opsForValue().get(id_c);
//        判断是否存在，不存在就返回空值
        if (StrUtil.isBlank(shop)) {
            return null;
        }
        //命中需要查看过期时间
        //反序列化
        RedisData redisData = JSONUtil.toBean(shop, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop1 = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断过期时间是否
        // 未过期
        if(expireTime.isAfter(LocalDateTime.now())){
//            未过期直接返回
            return shop1;
        }
        //已经过期：缓存重建
        //获取锁
        String lockKey = LOCK_SHOP_KEY+ id;
        boolean b = tryLock(lockKey);
        if (b){

            //成功就开启独立的线程，使用线程池
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
//                释放锁
                    unlock(lockKey);

                }
            });


        }
        //不管是否成功获取锁都会进行返回商铺的信息
        return shop1;

    }


    /**
     * 使用互斥锁解决缓存击穿的问题
     * @param id
     * @return
     */
    public Shop queryWithmutex(Long id){
        //        先进行查找缓存
        String id_c = CACHE_SHOP_KEY + id;
//        通过id进行查找
        String shop = stringRedisTemplate.opsForValue().get(id_c);
//        判断shop是否查找到，查到直接返回

        if (StrUtil.isNotBlank(shop)) {

//            查到将字符串进行反序列化，生成一个对象返回
            Shop shop_str = JSONUtil.toBean(shop, Shop.class);
            return shop_str;
        }
        //当查找的是”“则不可以继续进行数据库的查找，防止缓存穿透
        if(shop != null) {
            log.info("redis里面是空值，返回的是空值");
            return null;
        }
        log.info("进行查找");

//        没查到进行数据库的查找,此处进行修改，此时未命中缓存，需要尝试获取互斥锁。
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop_mysql = null;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取锁成功
            if(!isLock) {
                //如果没有获取成功就休眠等待，再调用
                Thread.sleep(50);
                return queryWithmutex(id);
            }
            //进行查询数据库


            shop_mysql = getById(id);
            //模拟重建的时间
            Thread.sleep(50);
            if(shop_mysql == null) {
    //            将空值返回redis.避免缓存穿透
                stringRedisTemplate.opsForValue().set(id_c,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
//        数据库存在，缓存不存在，将该数据缓存到redis中,注意此时该对象不可以直接存储到redis中，需要转换未json格式,设置时间
            stringRedisTemplate.opsForValue().set(id_c,JSONUtil.toJsonStr(shop_mysql),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        释放互斥锁
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {

            unlock(lockKey);
        }
        return shop_mysql;
    }

    /**
     * 把缓存空值封装为一个方法，解决缓存穿透
     * @param id
     * @return
     */

    public Shop queryWithPassThrough(Long id){
        //        先进行查找缓存
        String id_c = CACHE_SHOP_KEY + id;
//        通过id进行查找
        String shop = stringRedisTemplate.opsForValue().get(id_c);
//        判断shop是否查找到，查到直接返回
        if (StrUtil.isNotBlank(shop)) {

//            查到将字符串进行反序列化，生成一个对象返回
            Shop shop_str = JSONUtil.toBean(shop, Shop.class);
            return shop_str;
        }
        //当查找的是”“则不可以继续进行数据库的查找，防止缓存穿透
        if(shop != null) {
            log.info("redis里面是空值，返回的是空值");
            return null;
        }
        log.info("进行查找");

//        没查到进行数据库的查找
        Shop shop_mysql = getById(id);
        if(shop_mysql == null) {
//            将空值返回redis.避免缓存穿透
            stringRedisTemplate.opsForValue().set(id_c,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
//        数据库存在，缓存不存在，将该数据缓存到redis中,注意此时该对象不可以直接存储到redis中，需要转换未json格式,设置时间
        stringRedisTemplate.opsForValue().set(id_c,JSONUtil.toJsonStr(shop_mysql),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop_mysql;
    }
    //定义加锁的操作
    private boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        log.info("aBoolean"+aBoolean);
        return BooleanUtil.isTrue(aBoolean);

    }
    //定义删除锁即解锁的操作
    private void  unlock(String key){
        stringRedisTemplate.delete(key);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        //更新数据库,通过shop的id查询该商铺，先对数据库进行更新接着删除缓存
        Long id = shop.getId();
        String id_c = CACHE_SHOP_KEY + id;
        if (id == null) {
            return Result.fail("商户Id不能为空！");
        }
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(id_c);
        return Result.ok();
    }

    /**
     * 插入店铺信息及设置过期时间
     */
    public void saveShop2Redis(Long id, Long time) throws InterruptedException {
        Shop shop = getById(id);
        //模拟缓存重建的延迟时间
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
//        保存到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }


}
