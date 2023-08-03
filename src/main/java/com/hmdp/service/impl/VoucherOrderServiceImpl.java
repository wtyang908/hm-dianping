package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // 创建调用Lua脚本所需对象，因为给每个线程都创建io流很浪费资源和性能，所以这里使用静态属性的方式
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        // 创建RedisScript接口的实现类
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 读取类路径下的Lua脚本
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 指定返回值泛型，其实在创建对象的时候就可以指定泛型了
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder>orderTasks=new ArrayBlockingQueue<>(1024*1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //代理
    private IVoucherOrderService proxy;

    //代理对象

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //通过id查询优惠券时间是否开始
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //优惠活动未开始
//            Result.fail("优惠活动未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //优惠活动已结束
//            Result.fail("优惠活动已结束");
//        }
//        //判断库存是否充足
//        if(voucher.getStock()<1){
//            //库存不足
//            Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //使用自己定义的锁
//        //创建锁对象,因为对用户加锁，因此将key设置为这个
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//
//        //获取锁
////        boolean isLock = simpleRedisLock.tryLock(5);
//        //判断获取锁是否成功
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//
//        try{
//            //        synchronized (userId.toString().intern()){//保证在一个id对应一个锁
//            IVoucherOrderService voucherOrderService=(IVoucherOrderService) AopContext.currentProxy();//为了保证事务正确执行，应该使用这个事务的代理而不是this
//            return voucherOrderService.createVoucherOrder(voucherId);
////        }//锁加在这里的原因是锁在函数结束时释放，事务在函数结束时提交，可能锁释放而事务未提交，因此应先提交事务再释放锁
//        }finally {
//            //释放锁
////            simpleRedisLock.unLock();
//            lock.unlock();
//        }
//
//
//    }


    //使用Lua代码实现秒杀

    @PostConstruct  // Spring注解，在该类加载完成以后，马上执行该方法，因为读取阻塞队列的线程应该在秒杀之前就就绪了
    private void init(){
        // 提交线程任务，异步读取阻塞队列并写入数据库
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    // 内部类，线程要执行的读取阻塞队列任务
    private  class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    // 因为BlockingQueue.take()是专门用于读取阻塞队列的方法
                    // 所以阻塞队列为空时会自动阻塞，所以不用担心while循环给CPU带来压力
                    // 1.获取并删除队列中的头部(最上面一条)订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    // 异步线程执行写入数据库的任务之前要判断以及获取锁、释放锁，还有通过主线程的代理类(为了让事务生效)，调用执行写入数据库的方法
// 其实我觉得下面的锁在单线程要求下，其实没有意义，而且在主线程的Lua脚本中以及进行了判断，能下单的肯定都是有购买资格且库存充足的，并不惧怕并发引发问题，但是为了保险起见还是要写一下，同时万一以后要用到多个异步线程来处理呢
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户id
        // 这里把 UserHolder.getUser().getId()换成了voucherOrder.getUserId(),
        // 是因为这一步操作是有异步线程来完成的，他独立于主线程的，所以他的Threadlocal中不会有User对象
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取锁成功，把订单信息写入数据库
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        UserDTO user = UserHolder.getUser();

        //执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), user.getId().toString()
        );
        //判断结果
        int res=result.intValue();
        if(res!=0){
            return  Result.fail(res==1?"库存不足":"不能重复下单");
        }
        //有购买资格保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(user.getId());
        // 代金券id
        voucherOrder.setVoucherId(voucherId);

        //创建代理
        proxy=(IVoucherOrderService)AopContext.currentProxy();
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        return Result.ok(orderId);
    }

    @Transactional//事务,两个表操作
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单,只能用悲观锁,不能从userHolder获取，因为是异步线程
        Long userId = voucherOrder.getId();

            Integer count = query().eq("user_id",userId).eq("voucher_id", voucherOrder).count();
            if(count>0) {
                log.error("用户已经购买过一次");
                return;
            }

        //库存充足则扣减库存,通过乐观锁（更新的时候用）来防止超卖,只要库存数大于0就可以卖
        boolean sucess = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0).update();
        if(!sucess){
            log.error("库存不足");
            return ;
        }

        save(voucherOrder);


    }
}
