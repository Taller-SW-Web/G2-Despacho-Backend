package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "SEGURIDAD_URL=http://localhost:9999")
class ServicioGestionDespachosApplicationTests {

	@Test
	void contextLoads() {
	}

}
