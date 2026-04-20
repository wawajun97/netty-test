package com.example.netty_test;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NettyTestApplicationTests {
	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoads() {
		assertThat(dataSource).isNotNull();
	}

}
