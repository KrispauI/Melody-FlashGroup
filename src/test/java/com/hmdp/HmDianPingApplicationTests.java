package com.hmdp;

import javax.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.hmdp.service.impl.ShopServiceImpl;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    public void testSaveShopToRedis(){
        shopService.saveShopToRedis(1L, 20L);
    }

}