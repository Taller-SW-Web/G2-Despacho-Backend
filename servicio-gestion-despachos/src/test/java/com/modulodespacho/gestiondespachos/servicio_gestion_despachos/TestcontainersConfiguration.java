package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "pruebas.postgres.externo", havingValue = "false", matchIfMissing = true)
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));
	}

}
