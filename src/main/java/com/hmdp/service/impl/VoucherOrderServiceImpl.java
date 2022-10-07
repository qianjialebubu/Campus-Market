package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    /**
     * 主要实现对优惠券的秒杀
     * @param voucherId
     * @return
     */
    @Override

    public Result seckillVoucher(Long voucherId) {

        //1、查询优惠券信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        //2、判断是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        Integer stock = voucher.getStock();
        if (beginTime.isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }
        //3、判断是否结束
        if (endTime.isBefore(LocalDateTime.now())){
            return Result.fail("秒杀结束");
        }
        //4、判断库存
        if (stock<=0) {
            return Result.fail("库存不足");
        }

        //7、返回订单id,先获取锁后执行函数，获取锁-提交事务-释放锁，现在不能实现事务的功能，需要使用代理对象
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
//        //7、返回订单id,先获取锁后执行函数，获取锁-提交事务-释放锁，现在不能实现事务的功能，需要使用代理对象
//        Long userId = UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()) {
//
//        return createVoucherOrder(voucherId);}

    }

    /**
     * 单个jvm的可以实现锁
     * @param voucherId
     * @return
     */
    @Transactional
    public  Result createVoucherOrder(Long voucherId) {
        //5、一人一单，查询是否存在订单
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucherId", voucherId).count();
        //6.1 判断是否已经下过一个订单
        if (count > 0) {
            return Result.fail("该用户已经购买过此优惠券");
        }
        //6、扣减库存，使用乐观锁解决库存超卖的问题
        boolean voucher_id = iSeckillVoucherService.update().
                setSql("stock = stock -1 ").
                eq("voucher_id", voucherId).
                gt("stock", 0).//只要库存大于0就可以
                        update();
        if (!voucher_id) {
            return Result.fail("库存不足");
        }
        //6、创建订单
        VoucherOrder order = new VoucherOrder();
        //6.1 订单id
        Long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        //6.2 用户id
        Long id = UserHolder.getUser().getId();
        order.setUserId(id);
        //6.3 代金券id
        order.setVoucherId(voucherId);
        save(order);
        return Result.ok(orderId);

    }
}
