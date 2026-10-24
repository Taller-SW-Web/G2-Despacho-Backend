package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "pruebas.postgres.externo", havingValue = "false", matchIfMissing = true)
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// V1 instala PostGIS en extensions. Evitar la preinstalación de la imagen en public,
		// que haría que Flyway encontrara un esquema no vacío y otro namespace de geometry.
		return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.6")
				.asCompatibleSubstituteFor("postgres"))
				.withCopyToContainer(Transferable.of("# PostGIS se instala mediante V1 de Flyway.\n"),
						"/docker-entrypoint-initdb.d/10_postgis.sh");
	}

}
