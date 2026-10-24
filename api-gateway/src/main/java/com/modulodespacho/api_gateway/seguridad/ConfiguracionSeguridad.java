package com.modulodespacho.api_gateway.seguridad;

import java.time.Duration;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.util.Assert;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import com.modulodespacho.api_gateway.transversal.ProblemasGateway;
import reactor.core.publisher.Mono;

@Configuration
public class ConfiguracionSeguridad {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "despacho.demo.sin-autenticacion", havingValue = "false", matchIfMissing = true)
    ReactiveJwtDecoder decodificadorJwt(
            WebClient.Builder constructorCliente,
            @Value("${despacho.seguridad.url}") String urlSeguridad,
            @Value("${despacho.seguridad.audiencia}") String audiencia,
            @Value("${despacho.seguridad.algoritmo}") String algoritmo) {
        Assert.hasText(urlSeguridad, "Configure SEGURIDAD_URL con el origen del servicio de Seguridad");
        URI origen = URI.create(urlSeguridad);
        Assert.isTrue(("http".equalsIgnoreCase(origen.getScheme()) || "https".equalsIgnoreCase(origen.getScheme()))
                        && origen.getHost() != null && origen.getRawUserInfo() == null
                        && origen.getRawQuery() == null && origen.getRawFragment() == null
                        && (origen.getPath().isEmpty() || "/".equals(origen.getPath())),
                "SEGURIDAD_URL debe ser un origen HTTP(S), sin rutas, credenciales ni parámetros");
        SignatureAlgorithm algoritmoFirma = SignatureAlgorithm.from(algoritmo);
        Assert.notNull(algoritmoFirma, "Configure un algoritmo de firma asimétrica válido");
        WebClient cliente = constructorCliente
                .clientConnector(new ReactorClientHttpConnector(reactor.netty.http.client.HttpClient.create()
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .responseTimeout(Duration.ofSeconds(5))))
                .baseUrl(urlSeguridad.replaceAll("/+$", "")).build();

        // El descubrimiento se hace al recibir el primer token; salud no depende de Seguridad.
        Mono<NimbusReactiveJwtDecoder> decodificador = Mono.defer(() -> cliente.get()
                .uri("/api/v1/auth/.well-known/openid-configuration")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(5))
                .map(configuracion -> {
                    String emisor = (String) configuracion.get("issuer");
                    String claves = (String) configuracion.get("jwks_uri");
                    Assert.hasText(emisor, "Seguridad debe publicar issuer");
                    Assert.hasText(claves, "Seguridad debe publicar jwks_uri");
                    NimbusReactiveJwtDecoder resultado = NimbusReactiveJwtDecoder.withJwkSetUri(claves)
                            .jwsAlgorithm(algoritmoFirma).webClient(cliente).build();
                    resultado.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                            JwtValidators.createDefaultWithIssuer(emisor),
                            new ValidadorTokenDespacho(audiencia)));
                    return resultado;
                }))
                .cache(valor -> Duration.ofHours(1), error -> Duration.ZERO, () -> Duration.ZERO);

        return token -> decodificador.flatMap(actual -> actual.decode(token))
                .onErrorMap(error -> !(error instanceof BadJwtException),
                        error -> new BadJwtException("No fue posible validar el token", error));
    }

    @Bean
    SecurityWebFilterChain seguridad(ServerHttpSecurity http, ProblemasGateway problemas,
            @Value("${despacho.cors.origenes}") String origenes,
            @Value("${despacho.demo.sin-autenticacion:false}") boolean modoDemo) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origenes.split(",")).map(String::trim)
                .filter(origen -> !origen.isEmpty()).toList());
        cors.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept",
                "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(Arrays.asList("X-Correlation-Id", "WWW-Authenticate", "Retry-After"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource fuenteCors = new UrlBasedCorsConfigurationSource();
        fuenteCors.registerCorsConfiguration("/api/v1/**", cors);

        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(configuracion -> configuracion.configurationSource(fuenteCors))
                .authorizeExchange(reglas -> {
                    reglas.pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll();
                    reglas.pathMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll();
                    if (modoDemo) reglas.pathMatchers("/api/v1/**").permitAll();
                    else reglas.pathMatchers("/api/v1/**").authenticated();
                    reglas.anyExchange().denyAll();
                })
                .exceptionHandling(errores -> errores
                        .authenticationEntryPoint((intercambio, error) -> problemas.escribir(intercambio,
                                HttpStatus.UNAUTHORIZED, "DESP_ERROR_TOKEN_INVALIDO",
                                "Token no aceptado", "No fue posible autenticar la solicitud"))
                        .accessDeniedHandler((intercambio, error) -> problemas.escribir(intercambio,
                                HttpStatus.FORBIDDEN, "DESP_ERROR_SCOPE_INSUFICIENTE",
                                "Permiso insuficiente", "El cliente no tiene permiso para realizar esta operación")));
        if (!modoDemo) {
            http.oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> {})
                        .authenticationEntryPoint((intercambio, error) -> problemas.escribir(intercambio,
                                HttpStatus.UNAUTHORIZED, "DESP_ERROR_TOKEN_INVALIDO",
                                "Token no aceptado", "No fue posible autenticar la solicitud")));
        }
        return http.build();
    }
}
