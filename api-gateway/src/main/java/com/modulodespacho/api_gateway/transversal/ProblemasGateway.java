package com.modulodespacho.api_gateway.transversal;

import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProblemasGateway {

    private final JsonMapper json;

    public ProblemasGateway(JsonMapper json) {
        this.json = json;
    }

    public Mono<Void> escribir(ServerWebExchange intercambio, HttpStatus estado,
            String codigo, String titulo, String detalle) {
        if (intercambio.getResponse().isCommitted()) {
            return Mono.empty();
        }
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        problema.setInstance(URI.create(intercambio.getRequest().getPath().value()));
        problema.setProperty("code", codigo);
        problema.setProperty("correlationId", intercambio.getAttribute(FiltroCorrelacion.ATRIBUTO));
        problema.setProperty("timestamp", Instant.now());
        intercambio.getResponse().setStatusCode(estado);
        intercambio.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (estado == HttpStatus.UNAUTHORIZED) {
            intercambio.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return intercambio.getResponse().writeWith(Mono.fromSupplier(() ->
                intercambio.getResponse().bufferFactory().wrap(json.writeValueAsBytes(problema))));
    }
}
