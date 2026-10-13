package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import org.springframework.boot.SpringApplication;

public class TestServicioOperacionRepartoApplication {

	public static void main(String[] args) {
		SpringApplication.from(ServicioOperacionRepartoApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
