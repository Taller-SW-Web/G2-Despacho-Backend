package com.modulodespacho.api_gateway.transversal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Order(-50)
public class FiltroTamanoSolicitud implements WebFilter {

    private final int maximoBytes;
    private final ProblemasGateway problemas;

    public FiltroTamanoSolicitud(@Value("${despacho.solicitudes.maximo-bytes}") int maximoBytes,
            ProblemasGateway problemas) {
        Assert.isTrue(maximoBytes > 0, "El tamaño máximo de solicitud debe ser positivo");
        this.maximoBytes = maximoBytes;
        this.problemas = problemas;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, WebFilterChain cadena) {
        if (!intercambio.getRequest().getPath().value().startsWith("/api/v1/")) {
            return cadena.filter(intercambio);
        }
        if (intercambio.getRequest().getHeaders().getContentLength() > maximoBytes) {
            return rechazar(intercambio);
        }
        // Limita también cuerpos por fragmentos que no declaran Content-Length.
        return DataBufferUtils.join(intercambio.getRequest().getBody(), maximoBytes)
                .map(datos -> {
                    byte[] contenido = new byte[datos.readableByteCount()];
                    try {
                        datos.read(contenido);
                    } finally {
                        DataBufferUtils.release(datos);
                    }
                    return contenido;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(contenido -> cadena.filter(intercambio.mutate()
                        .request(new ServerHttpRequestDecorator(intercambio.getRequest()) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.defer(() -> Flux.just(
                                        intercambio.getResponse().bufferFactory().wrap(contenido)));
                            }
                        }).build()))
                .onErrorResume(DataBufferLimitException.class, error -> rechazar(intercambio));
    }

    private Mono<Void> rechazar(ServerWebExchange intercambio) {
        return problemas.escribir(intercambio, HttpStatus.CONTENT_TOO_LARGE,
                "DESP_ERROR_SOLICITUD_DEMASIADO_GRANDE", "Solicitud demasiado grande",
                "El cuerpo de la solicitud supera el tamaño permitido");
    }
}
