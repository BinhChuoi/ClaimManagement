package com.fightforfuture.cmp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CmpApplication {

	public static void main(String[] args) {
		SpringApplication.run(CmpApplication.class, args);
	}

}
