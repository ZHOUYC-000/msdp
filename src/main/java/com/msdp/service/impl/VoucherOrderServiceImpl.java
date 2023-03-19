package com.msdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.msdp.dto.Result;
import com.msdp.entity.VoucherOrder;
import com.msdp.mapper.VoucherOrderMapper;
import com.msdp.service.ISeckillVoucherService;
import com.msdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.msdp.utils.RedisIdWorker;
import com.msdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Eason
 * @since 2023-03-16
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    /**
     * 全局ID生成器
     */
    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate template;

    @Resource
    private RedissonClient redissonClient;

    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * Lua脚本，解决秒杀
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        // 静态代码块，初始化
        SECKILL_SCRIPT= new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 单工作线程的线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        // 后台开启一个工作线程，不停从消息队列中获取消息，下单
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        // 消息队列名称
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的消息
                    List<MapRecord<String, Object, Object>> list = template.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 失败
                        continue;
                    }
                    // 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    //确认
                    template.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                    handlePendingList();
                }

            }
        }

        /**
         * 处理所有未确认的消息
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 获取消息队列中的消息
                    List<MapRecord<String, Object, Object>> list = template.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    // 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    //确认
                    template.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    /**
     * 利用Redisson实现分布式锁
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder){
        Long id = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + id);
        //获取锁
        //boolean isLock = redisLock.tryLock(12);
        // 默认30秒
        boolean isLock = lock.tryLock();
        if(!isLock){
            // 获取锁失败直接返回
            return;
        }
        try {
            createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    /**
     * 使用lua脚本执行秒杀业务，成功下单后将消息存入消息队列中，由耗时的下单操作由后台线程完成
     * 超卖问题，在Redis中缓存库存大小
     * 一人一单问题，利用set集合存储已下单用户，下单前判断用户是否已经下过单
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long id = UserHolder.getUser().getId();
        long orderId = idWorker.nextId("order");
        System.out.println(id);
        // 执行lua脚本
        Long result = template.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                id.toString(),
                String.valueOf(orderId)
        );
        // 判断结果是否为0
        // 不为0
        if(result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        // 返回订单id
        return Result.ok(orderId);

    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return ;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            return ;
        }

        save(voucherOrder);

    }

}
