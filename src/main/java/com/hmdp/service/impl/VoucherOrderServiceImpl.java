package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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

//高并发情况下可能会造成超卖，解决方法加锁，乐观锁有版本发和CAS法（把版本换为库存）
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource//获取全局唯一订单id
    private RedisIdWorker redisIdWorker;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //通过id查询优惠券时间是否开始
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //优惠活动未开始
            Result.fail("优惠活动未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //优惠活动已结束
            Result.fail("优惠活动已结束");
        }
        //判断库存是否充足
        if(voucher.getStock()<1){
            //库存不足
            Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //使用自己定义的锁
        //创建锁对象,因为对用户加锁，因此将key设置为这个
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();

        //获取锁
//        boolean isLock = simpleRedisLock.tryLock(5);
        //判断获取锁是否成功
        if(!isLock){
            return Result.fail("不允许重复下单");
        }

        try{
            //        synchronized (userId.toString().intern()){//保证在一个id对应一个锁
            IVoucherOrderService voucherOrderService=(IVoucherOrderService) AopContext.currentProxy();//为了保证事务正确执行，应该使用这个事务的代理而不是this
            return voucherOrderService.createVoucherOrder(voucherId);
//        }//锁加在这里的原因是锁在函数结束时释放，事务在函数结束时提交，可能锁释放而事务未提交，因此应先提交事务再释放锁
        }finally {
            //释放锁
//            simpleRedisLock.unLock();
            lock.unlock();
        }


    }

    @Transactional//事务,两个表操作
    public Result createVoucherOrder(Long voucherId) {
        //一人一单,只能用悲观锁
        Long userId = UserHolder.getUser().getId();

            Integer count = query().eq("user_id",userId).eq("voucher_id", voucherId).count();
            if(count>0)
                return Result.fail("用户已经购买过一次");


        //库存充足则扣减库存,通过乐观锁（更新的时候用）来防止超卖,只要库存数大于0就可以卖
        boolean sucess = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock",0).update();
        if(!sucess){
            Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //通过自己定义的redisIdWorker获取全局唯一id
        long orderId = redisIdWorker.nextId("order");
        //填入订单参数
        voucherOrder.setId(orderId).setUserId(UserHolder.getUser().getId()).setVoucherId(voucherId);
        //写入数据库
        save(voucherOrder);
        return Result.ok(orderId);

    }
}
