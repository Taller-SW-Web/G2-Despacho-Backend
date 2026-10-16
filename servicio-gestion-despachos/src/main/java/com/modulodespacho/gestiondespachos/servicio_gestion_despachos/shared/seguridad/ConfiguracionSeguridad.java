package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import java.net.http.HttpClient;
import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure.FiltroLimiteCotizaciones;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.RespuestasSeguridad;

@Configuration
@EnableMethodSecurity
public class ConfiguracionSeguridad {

    @Bean
    JwtDecoder decodificadorJwt(
            @Value("${despacho.seguridad.url}") String urlSeguridad,
            @Value("${despacho.seguridad.audiencia}") String audiencia,
            @Value("${despacho.seguridad.algoritmo}") String algoritmo) {
        Assert.hasText(urlSeguridad, "Configure SEGURIDAD_URL o active explícitamente compose.seguridad-local.yaml");
        URI origen = URI.create(urlSeguridad);
        Assert.isTrue(("http".equalsIgnoreCase(origen.getScheme()) || "https".equalsIgnoreCase(origen.getScheme()))
                        && origen.getHost() != null && origen.getRawUserInfo() == null
                        && origen.getRawQuery() == null && origen.getRawFragment() == null
                        && (origen.getPath().isEmpty() || "/".equals(origen.getPath())),
                "SEGURIDAD_URL debe ser un origen HTTP(S), sin rutas, credenciales ni parámetros");
        SignatureAlgorithm algoritmoFirma = SignatureAlgorithm.from(algoritmo);
        Assert.notNull(algoritmoFirma, "Configure un algoritmo de firma asimétrica válido");
        JdkClientHttpRequestFactory peticiones = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        peticiones.setReadTimeout(Duration.ofSeconds(5));
        RestClient cliente = RestClient.builder().requestFactory(peticiones)
                .baseUrl(urlSeguridad.replaceAll("/+$", "")).build();
        JwtDecoder diferido = new SupplierJwtDecoder(() -> {
            Map<String, Object> configuracion = cliente.get()
                    .uri("/api/v1/auth/.well-known/openid-configuration").retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            Assert.notNull(configuracion, "Seguridad debe publicar su configuración");
            String emisor = (String) configuracion.get("issuer");
            String claves = (String) configuracion.get("jwks_uri");
            Assert.hasText(emisor, "Seguridad debe publicar issuer");
            Assert.hasText(claves, "Seguridad debe publicar jwks_uri");
            NimbusJwtDecoder resultado = NimbusJwtDecoder.withJwkSetUri(claves)
                    .jwsAlgorithm(algoritmoFirma).restOperations(
                            new org.springframework.web.client.RestTemplate(peticiones)).build();
            resultado.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(emisor), new ValidadorTokenDespacho(audiencia)));
            return resultado;
        });
        return token -> {
            try {
                return diferido.decode(token);
            } catch (JwtException | JwtDecoderInitializationException error) {
                throw new BadJwtException("No fue posible validar el token", error);
            }
        };
    }

    @Bean
    SecurityFilterChain seguridad(HttpSecurity http, RespuestasSeguridad respuestas,
            ObjectProvider<FiltroLimiteCotizaciones> filtroLimite,
            @Value("${springdoc.api-docs.enabled:false}") boolean documentacionHabilitada) throws Exception {
        FiltroLimiteCotizaciones limite = filtroLimite.getIfAvailable();
        if (limite != null) {
            http.addFilterAfter(limite, AuthorizationFilter.class);
        }
        return http.csrf(configuracion -> configuracion.disable())
                .httpBasic(configuracion -> configuracion.disable())
                .formLogin(configuracion -> configuracion.disable())
                .logout(configuracion -> configuracion.disable())
                .requestCache(configuracion -> configuracion.disable())
                .sessionManagement(configuracion -> configuracion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reglas -> {
                    reglas.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll();
                    if (documentacionHabilitada) {
                        reglas.requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll();
                    }
                    reglas
                        .requestMatchers(HttpMethod.POST, "/api/v1/cotizaciones").access(servicioConScope("cotizaciones:calcular"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/despachos").access(servicioConScope("despachos:crear"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/despachos/pedidos/*/cancelacion").access(servicioConScope("despachos:cancelar"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/seguimientos/pedidos/*").access(servicioConScope("seguimientos:leer"))
                        .requestMatchers("/api/v1/zonas/**", "/api/v1/distritos/**", "/api/v1/despachos/**")
                        .hasRole("GESTOR_DESPACHO")
                        .anyRequest().denyAll();
                })
                .exceptionHandling(errores -> errores
                        .authenticationEntryPoint(respuestas).accessDeniedHandler(respuestas))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(convertidorAutenticacion()))
                        .authenticationEntryPoint(respuestas).accessDeniedHandler(respuestas))
                .build();
    }

    private JwtAuthenticationConverter convertidorAutenticacion() {
        JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(token -> {
            List<GrantedAuthority> permisos = new ArrayList<>();
            if ("servicio".equals(token.getClaimAsString("tipo"))) {
                permisos.addAll(new JwtGrantedAuthoritiesConverter().convert(token));
            } else if ("acceso".equals(token.getClaimAsString("tipo"))) {
                List<String> roles = token.getClaimAsStringList("roles");
                if (roles != null) {
                    roles.forEach(rol -> permisos.add(new SimpleGrantedAuthority("ROLE_" + rol)));
                }
            }
            return permisos;
        });
        return convertidor;
    }

    private AuthorizationManager<RequestAuthorizationContext> servicioConScope(String scope) {
        return (autenticacion, contexto) -> {
            Authentication identidad = autenticacion.get();
            boolean autorizado = esTokenDeTipo(identidad, "servicio") && identidad.getAuthorities()
                    .stream().anyMatch(permiso -> permiso.getAuthority().equals("SCOPE_" + scope));
            return new AuthorizationDecision(autorizado);
        };
    }

    private boolean esTokenDeTipo(Authentication identidad, String tipo) {
        return identidad instanceof JwtAuthenticationToken jwt && identidad.isAuthenticated()
                && tipo.equals(jwt.getToken().getClaimAsString("tipo"));
    }
}
