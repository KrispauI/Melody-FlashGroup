package com.hmdp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.Redis_IDWorker;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private Redis_IDWorker redis_IDWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);


    @Test
    public void testNextId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(20);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redis_IDWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("end - start = " + (end - start));
    }

    @Test
    public void testSaveShopToRedis(){
        shopService.saveShopToRedis(1L, 20L);
    }

}