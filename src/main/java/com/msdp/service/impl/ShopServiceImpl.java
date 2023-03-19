package com.msdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.msdp.dto.Result;
import com.msdp.entity.Shop;
import com.msdp.mapper.ShopMapper;
import com.msdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.msdp.utils.CacheClint;
import com.msdp.utils.RedisConstants;
import com.msdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Eason
 * @since 2023-03-15
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate template;

    @Resource
    private CacheClint cacheClint;


    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 解决缓存击穿
        // Shop shop = queryWithMutex(id);
        // Shop shop = queryWithLogicalExpired(id);
        // Shop shop = cacheClint.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
        //                                            this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        Shop shop = cacheClint.queryWithLogicalExpired(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                                                        this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if(shop == null)
            return Result.fail("店铺不存在");
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        template.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
    /**
    public Shop queryWithLogicalExpired(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = template.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
           return null;
        }
        // 命中，序列化JSON
        RedisData data = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject object = (JSONObject) data.getData();
        Shop shop = JSONUtil.toBean(object, Shop.class);
        LocalDateTime expireTime = data.getExpireTime();
        // 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return shop;
        }

        //已过期，缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //成功，执行缓存重建
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShopToRedis(id, 20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //失败，直接返回
        return shop;
    }**/

    /**
    public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = template.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 是否是空值，解决缓存穿透问题
        if(shopJson != null){
            return null;
        }
        // 实现缓存重建
        // 解决缓存击穿
        // 获取互斥锁

        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // System.out.println("islock :" + isLock);
            // 判断是否成功
            if (!isLock) {
                // 失败则休眠
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);

            // 模拟重建延迟
            Thread.sleep(200);

            if (shop == null) {
                // 空值写入redis
                template.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            template.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (Exception e){
            e.printStackTrace();
        }
        // 释放锁
        unlock(lockKey);
        return shop;
    }
    **/
    /**
     * 解决缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = template.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 是否是空值，解决缓存穿透问题
        if(shopJson != null){
            return null;
        }

        Shop shop = getById(id);
        if(shop == null){
            // 空值写入redis
            template.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        template.opsForValue().set(key,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
     */


    public void saveShopToRedis(Long id, Long expireSeconds){
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        template.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
