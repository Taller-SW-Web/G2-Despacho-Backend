package com.modulodespacho.api_gateway.transversal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Marca las solicitudes de negocio que pasan por el Gateway con un secreto compartido.
 *
 * <p>Gestión y Operación rechazan las llamadas directas a {@code /api/v1/**} que no lo traen, lo
 * que simula el enmascaramiento de sus URLs públicas en Render Free. La cabecera que envíe el
 * cliente siempre se descarta; nunca se reenvía a Seguridad ({@code /api/v1/auth/**}).
 */
@Component
@Order(-60)
public class FiltroCabeceraInterna implements WebFilter {

    public static final String ENCABEZADO = "X-Despacho-Interno";

    private final String secreto;

    public FiltroCabeceraInterna(@Value("${despacho.interno.secreto:}") String secreto) {
        this.secreto = secreto == null ? "" : secreto.trim();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, WebFilterChain cadena) {
        boolean marcar = !secreto.isEmpty() && aplica(intercambio.getRequest().getPath().value());
        return cadena.filter(
                intercambio
                        .mutate()
                        .request(
                                peticion ->
                                        peticion.headers(
                                                encabezados -> {
                                                    encabezados.remove(ENCABEZADO);
                                                    if (marcar) {
                                                        encabezados.set(ENCABEZADO, secreto);
                                                    }
                                                }))
                        .build());
    }

    static boolean aplica(String ruta) {
        return ruta.startsWith("/api/v1/")
                && !ruta.equals("/api/v1/auth")
                && !ruta.startsWith("/api/v1/auth/");
    }
}
