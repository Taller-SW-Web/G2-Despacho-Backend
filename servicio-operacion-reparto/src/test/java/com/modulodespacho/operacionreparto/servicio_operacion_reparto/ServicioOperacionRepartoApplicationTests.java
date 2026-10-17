package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import static org.assertj.core.api.Assertions.assertThat;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure.AutorizadorCargaSupabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "SEGURIDAD_URL=http://localhost:9999")
class ServicioOperacionRepartoApplicationTests {

	@Autowired
	private ApplicationContext contexto;

	@Test
	void iniciaConClienteRestYAutorizadorRealDeEvidencias() {
		assertThat(contexto.getBean(RestClient.Builder.class)).isNotNull();
		assertThat(contexto.getBean(AutorizadorCargaSupabase.class)).isNotNull();
	}

}
