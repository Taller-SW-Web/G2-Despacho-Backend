package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import org.springframework.boot.SpringApplication;

public class TestServicioGestionDespachosApplication {

	public static void main(String[] args) {
		SpringApplication.from(ServicioGestionDespachosApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
