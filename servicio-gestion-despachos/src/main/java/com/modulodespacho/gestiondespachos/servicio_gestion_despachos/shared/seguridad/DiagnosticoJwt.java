package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.text.ParseException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.core.codec.CodecException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.web.client.RestClientResponseException;

public final class DiagnosticoJwt {

    private DiagnosticoJwt() {}

    public static String describir(Throwable error, boolean tieneAutorizacion) {
        boolean falloClaves = false;
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof FalloConsulta consulta) return consulta.diagnostico;
            if (causa instanceof RemoteKeySourceException) falloClaves = true;
            if (falloClaves) continue;
            if (causa instanceof JwtValidationException validacion) {
                String motivos =
                        validacion.getErrors().stream()
                                .map(fallo -> motivoValidacion(fallo.getDescription()))
                                .distinct()
                                .collect(Collectors.joining(","));
                return "etapa=validacion_jwt motivo=" + motivos;
            }
            if (causa instanceof BadJWSException)
                return "etapa=validacion_jwt motivo=firma_invalida";
            if (causa instanceof BadJOSEException)
                return "etapa=validacion_jwt motivo=firma_o_clave_no_aceptada";
            if (causa instanceof ParseException)
                return "etapa=validacion_jwt motivo=formato_invalido";
        }
        if (falloClaves) return "etapa=jwks motivo=claves_no_disponibles";
        return "etapa=validacion_jwt motivo="
                + (tieneAutorizacion ? "token_no_aceptado" : "token_ausente");
    }

    private static String motivoValidacion(String descripcion) {
        if (descripcion == null) return "claims_invalidos";
        if (descripcion.startsWith("Jwt expired at ")) return "vencido";
        if (descripcion.startsWith("Jwt used before ")) return "todavia_no_vigente";
        if (descripcion.equals("The iss claim is not valid")) return "emisor_incorrecto";
        if (descripcion.equals(ValidadorTokenDespacho.AUDIENCIA_INCORRECTA))
            return "audiencia_incorrecta";
        return "claims_invalidos";
    }

    public static boolean tiempoAgotado(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof TimeoutException
                    || causa instanceof SocketTimeoutException
                    || causa instanceof HttpTimeoutException) return true;
        }
        return false;
    }

    static final class FalloConsulta extends RuntimeException {
        private final String diagnostico;

        FalloConsulta(String etapa, long inicio, Throwable causa) {
            this(
                    etapa,
                    inicio,
                    causa instanceof RestClientResponseException respuesta
                            ? respuesta.getStatusCode().value()
                            : null,
                    causa);
        }

        private FalloConsulta(String etapa, long inicio, Integer estado, Throwable causa) {
            super("No fue posible consultar Seguridad", causa);
            String motivo =
                    estado != null
                            ? "respuesta_http"
                            : tiempoAgotado(causa)
                                    ? "tiempo_agotado"
                                    : causa instanceof IllegalArgumentException
                                                    || causa instanceof ClassCastException
                                                    || causa instanceof CodecException
                                            ? "respuesta_invalida"
                                            : "consulta_fallida";
            diagnostico =
                    "etapa="
                            + etapa
                            + " motivo="
                            + motivo
                            + " estado_g7="
                            + (estado == null ? "-" : estado)
                            + " duracion_g7_ms="
                            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
        }
    }
}
