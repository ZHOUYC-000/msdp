package com.msdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClint {
    
    @Resource
    private StringRedisTemplate template;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(1);

    /**
     * 最基本的set方法，直接传入对象即可
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        template.opsForValue().set(key, JSONUtil.toJsonStr(value) , time, unit);
    }

    /**
     * 逻辑过期，封装了存储数据的格式：RedisData，在原有value的基础上增加了过期字段。该key在Redis中是不过期的
     * （存在的问题，需要后台线程去检查key的逻辑过期情况，实现较为复杂，这里比较简略的在读取时进行的判断）
     * 逻辑过期字段是当前时间加上ttl秒数生成的一个时间戳
     *
     * 可以解决缓存击穿问题和缓存雪崩问题中大量key过期的情况
     * 缓存雪崩中Redis宕机的情况需要利用集群来解决，这里先不做讨论
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        template.opsForValue().set(key, JSONUtil.toJsonStr(redisData) , time, unit);
    }

    /**
     * 查询，利用缓存空值，解决缓存穿透问题。
     * 这里需要用到两个泛型，ID是传入参数，这个不确定，R是返回类型，也不确定。
     * 这里还用到了函数式编程
     * Redis查询结果未通过isNotBlank只有两种可能，null和空值，空值直接返回null
     * 这里需要注意的是，为了防止数据不一致的情况发生，需要在缓存空值的时候加一个较短的过期时间
     *
     * 其他解决方案：布隆过滤器、参数值的合法性检查
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallBack, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = template.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        // 是否是空值，解决缓存穿透问题
        if(json != null){
            return null;
        }
        R r = dbFallBack.apply(id);

        if(r == null){
            // 空值写入redis
            template.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿这一情况下读取数据，在逻辑过期的情况下，数据一般都是进行缓存预热的，所以set操作一般不会去做。
     * 当没有从Redis中读取到数据的时候，我们一般认为数据库里也没有这条数据，直接返回null就好了（在一定程度上解决了缓存穿透？）
     * 在Redis中命中数据后，那么就需要进行过期判断，当前时间戳和逻辑过期字段时间戳比较。未过期则直接返回。
     * 若过期，则需要重建缓存
     * 首先获取分布式锁，成功后利用线程池，在后台构建缓存。失败后，直接返回
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithLogicalExpired(String keyPrefix, ID id, Class<R> type,
                                             Function<ID, R> dbFallBack, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = template.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            return null;
        }
        // 命中，序列化JSON
        RedisData data = JSONUtil.toBean(json, RedisData.class);
        JSONObject object = (JSONObject) data.getData();
        R r = JSONUtil.toBean(object, type);
        LocalDateTime expireTime = data.getExpireTime();
        // 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return r;
        }

        //已过期，缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //成功，执行缓存重建
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 查询数据库
                    R r1 = dbFallBack.apply(id);
                    // 写入redis
                    RedisData redisData = new RedisData();
                    redisData.setData(r1);
                    redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
                    this.set(key, redisData, time, unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //失败，直接返回
        return r;
    }

    /**
     *
     * 分布式锁，利用NX命令，在Redis中存储一个锁字段，value不重要，重要的是key，所有线程通过这一key尝试获取锁，如果key已存在，获取锁失败
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = template.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放分布式锁，删除这个key即可
     * @param key
     */
    private void unlock(String key){
        template.delete(key);
    }
}
