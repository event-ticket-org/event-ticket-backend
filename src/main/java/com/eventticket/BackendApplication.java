package com.eventticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// Records bind by constructor, which needs scanning rather than a @Component on each one.
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
