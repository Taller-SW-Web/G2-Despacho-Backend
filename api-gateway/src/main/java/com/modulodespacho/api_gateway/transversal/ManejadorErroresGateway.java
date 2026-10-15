package com.modulodespacho.api_gateway.transversal;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class ManejadorErroresGateway implements WebExceptionHandler {

    private final ProblemasGateway problemas;

    public ManejadorErroresGateway(ProblemasGateway problemas) {
        this.problemas = problemas;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange intercambio, Throwable error) {
        if (intercambio.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        if (esDependenciaNoDisponible(error)) {
            return problemas.escribir(intercambio, HttpStatus.SERVICE_UNAVAILABLE,
                    "DESP_ERROR_SERVICIO_NO_DISPONIBLE", "Servicio no disponible",
                    "El servicio de destino no está disponible temporalmente");
        }
        if (error instanceof ResponseStatusException respuesta) {
            HttpStatus estado = HttpStatus.resolve(respuesta.getStatusCode().value());
            return problemas.escribir(intercambio, estado == null ? HttpStatus.INTERNAL_SERVER_ERROR : estado,
                    "DESP_ERROR_SOLICITUD_NO_PROCESADA", "Solicitud no procesada",
                    "No fue posible procesar la solicitud");
        }
        return problemas.escribir(intercambio, HttpStatus.INTERNAL_SERVER_ERROR,
                "DESP_ERROR_INTERNO", "Error interno", "No fue posible procesar la solicitud");
    }

    private boolean esDependenciaNoDisponible(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConnectException || causa instanceof TimeoutException
                    || causa instanceof io.netty.handler.timeout.ReadTimeoutException
                    || causa instanceof java.net.UnknownHostException
                    || causa instanceof reactor.netty.http.client.PrematureCloseException
                    || causa instanceof ResponseStatusException respuesta
                        && (respuesta.getStatusCode().value() == 502 || respuesta.getStatusCode().value() == 504)) {
                return true;
            }
            if (causa == causa.getCause()) {
                break;
            }
        }
        return false;
    }
}
