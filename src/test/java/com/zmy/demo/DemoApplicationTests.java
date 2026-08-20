package com.zmy.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "wechat.auto-login=false")
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
