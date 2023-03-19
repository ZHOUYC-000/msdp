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

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(100);

    public void set(String key, Object value, Long time, TimeUnit unit){
        template.opsForValue().set(key, JSONUtil.toJsonStr(value) , time, unit);
    }


    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        template.opsForValue().set(key, JSONUtil.toJsonStr(redisData) , time, unit);
    }

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

    private boolean tryLock(String key){
        Boolean flag = template.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        template.delete(key);
    }
}
