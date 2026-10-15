package com.modulodespacho.api_gateway.transversal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacion implements WebFilter {

    public static final String ENCABEZADO = "X-Correlation-Id";
    public static final String ATRIBUTO = "despacho.correlacion";
    private static final Logger REGISTRO = LoggerFactory.getLogger(FiltroCorrelacion.class);

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, WebFilterChain cadena) {
        String recibido = intercambio.getRequest().getHeaders().getFirst(ENCABEZADO);
        String correlacion = recibido != null && recibido.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                ? recibido : UUID.randomUUID().toString();
        intercambio.getAttributes().put(ATRIBUTO, correlacion);
        intercambio.getResponse().getHeaders().set(ENCABEZADO, correlacion);
        long inicio = System.nanoTime();
        intercambio.getResponse().beforeCommit(() -> {
            intercambio.getResponse().getHeaders().set(ENCABEZADO, correlacion);
            REGISTRO.info("Solicitud {} finalizada con estado {} en {} ms. Correlación: {}",
                    intercambio.getRequest().getMethod(), intercambio.getResponse().getStatusCode() == null
                            ? 200 : intercambio.getResponse().getStatusCode().value(),
                    (System.nanoTime() - inicio) / 1_000_000, correlacion);
            return Mono.empty();
        });
        return cadena.filter(intercambio.mutate().request(peticion ->
                peticion.headers(encabezados -> encabezados.set(ENCABEZADO, correlacion))).build());
    }
}
