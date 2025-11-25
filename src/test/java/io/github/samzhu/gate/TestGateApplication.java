package io.github.samzhu.gate;

import org.springframework.boot.SpringApplication;

public class TestGateApplication {

	public static void main(String[] args) {
		SpringApplication.from(GateApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
