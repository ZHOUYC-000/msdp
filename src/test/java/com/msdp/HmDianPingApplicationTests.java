package com.msdp;

import cn.hutool.json.JSONUtil;
import com.msdp.dto.UserDTO;
import com.msdp.entity.User;
import com.msdp.service.impl.ShopServiceImpl;
import com.msdp.service.impl.UserServiceImpl;
import com.msdp.utils.RedisConstants;
import com.msdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl service;

    @Resource
    private UserServiceImpl userService;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate template;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testShop(){
        service.saveShopToRedis(1L, 10L);
    }

    @Test
    void testWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long id = idWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time :" + (end - begin));
    }

    @Test
    void addToken(){
        List<User> list = userService.list();
        String fileName = "D:\\token.txt";
        FileWriter fw = null;
        try {
            //如果文件存在，则追加内容；如果文件不存在，则创建文件
            File f = new File(fileName);
            fw = new FileWriter(f, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        PrintWriter pw = new PrintWriter(fw);


        for(User user: list){
            String phone = user.getPhone();
            String token = UUID.randomUUID().toString();
            pw.println(token);
            pw.flush();
            UserDTO userDTO = new UserDTO();
            BeanUtils.copyProperties(user, userDTO);
            String userJson = JSONUtil.toJsonStr(userDTO);
            template.opsForValue().set(RedisConstants.LOGIN_USER_KEY + token ,
                    userJson);
        }
        try {
            fw.flush();
            pw.close();
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
