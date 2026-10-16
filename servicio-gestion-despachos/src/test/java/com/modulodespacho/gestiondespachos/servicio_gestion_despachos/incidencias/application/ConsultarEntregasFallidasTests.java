package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.FiltrosConsultaFallidos.Recepcion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.EstadoJornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Jornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Repartidor;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal.Motivo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Puertos simulados: verifica el caso de uso, sin acreditar HTTP, autenticación ni servicios reales. */
class ConsultarEntregasFallidasTests {

    private static final Instant AHORA = Instant.parse("2026-10-16T18:00:00Z");
    private LectorIncidenciasLocales locales;
    private LectorDatosOperativos operacion;
    private CatalogoMotivosFallo motivos;
    private ConsultarEntregasFallidas consulta;
    private Map<String, EstadoJornada> estados;
    private boolean nombresDisponibles;

    @BeforeEach
    void preparar() {
        locales = mock(LectorIncidenciasLocales.class);
        operacion = mock(LectorDatosOperativos.class);
        motivos = mock(CatalogoMotivosFallo.class);
        estados = new HashMap<>();
        nombresDisponibles = true;
        when(motivos.existeCodigo(anyString())).thenReturn(true);
        when(locales.consultarBase(any(), any(), any())).thenReturn(List.of());
        when(operacion.consultar(anyCollection(), anyCollection())).thenAnswer(invocacion -> {
            Collection<String> idsRepartidor = invocacion.getArgument(0);
            Collection<String> idsJornada = invocacion.getArgument(1);
            Map<String, Repartidor> repartidores = new LinkedHashMap<>();
            Map<String, Jornada> jornadas = new LinkedHashMap<>();
            idsRepartidor.forEach(id -> repartidores.put(id, new Repartidor(id, nombresDisponibles ? "Nombre de prueba" : null)));
            idsJornada.forEach(id -> {
                EstadoJornada estado = estados.getOrDefault(id, EstadoJornada.DESCONOCIDA);
                jornadas.put(id, new Jornada(id, estado, estado == EstadoJornada.CERRADA ? AHORA.minusSeconds(3600) : null, AHORA));
            });
            return new DatosOperativos(repartidores, jornadas);
        });
        consulta = new ConsultarEntregasFallidas(locales, operacion, motivos, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void calculaSieteCincoDosAntesDelGrupoYPaginaYConsultaNombresSoloParaLaPagina() {
        List<IncidenciaListadoLocal> base = new ArrayList<>();
        for (int i = 1; i <= 5; i++) base.add(fila("R-0" + i, true, AHORA.minusSeconds(1000)));
        for (int i = 1; i <= 7; i++) {
            IncidenciaListadoLocal pendiente = fila("P-0" + i, false, AHORA.minusSeconds(2000));
            base.add(pendiente);
            estados.put(pendiente.idJornada(), i <= 2 ? EstadoJornada.CERRADA : EstadoJornada.ACTIVA);
        }
        Collections.reverse(base);
        when(locales.consultarBase(null, null, null)).thenReturn(base);

        PaginaEntregasFallidas resultado = consulta.consultar(filtros(3, 2, Recepcion.TODOS, false));

        assertThat(resultado.indicadores()).isEqualTo(new PaginaEntregasFallidas.Indicadores(7, 5, 2L, 0));
        assertThat(resultado.totalElementos()).isEqualTo(12);
        assertThat(resultado.totalPaginas()).isEqualTo(6);
        assertThat(resultado.contenido()).extracting(PaginaEntregasFallidas.Fila::idDespacho).containsExactly("R-05", "P-01");
        assertThat(resultado.contenido().getLast().retornoAtrasado()).isTrue();
        verify(operacion).consultar(eq(List.of()), argThat(ids -> ids.size() == 7 && ids.contains("JOR-P-07")));
        verify(operacion).consultar(List.of("REP-R-05", "REP-P-01"), List.of("JOR-R-05"));

        PaginaEntregasFallidas recibidos = consulta.consultar(filtros(1, 20, Recepcion.RECIBIDO_EN_CENTRO, false));
        assertThat(recibidos.totalElementos()).isEqualTo(5);
        assertThat(recibidos.indicadores()).isEqualTo(resultado.indicadores());
        assertThat(recibidos.contenido()).allSatisfy(fila -> assertThat(fila.retornoAtrasado()).isFalse());
        assertThat(recibidos.consultadoEn()).isEqualTo(AHORA);
    }

    @Test
    void ordenaPorAntiguedadYDesempateYConservaTotalesEnPaginasPosterioresAlFinal() {
        when(locales.consultarBase(null, null, null)).thenReturn(List.of(
                fila("R-Z", true, AHORA), fila("R-B", true, AHORA), fila("R-A", true, AHORA.minusSeconds(1))));

        assertThat(consulta.consultar(filtros(1, 1, Recepcion.TODOS, false)).contenido().getFirst().idDespacho()).isEqualTo("R-A");
        assertThat(consulta.consultar(filtros(2, 1, Recepcion.TODOS, false)).contenido().getFirst().idDespacho()).isEqualTo("R-B");
        PaginaEntregasFallidas fuera = consulta.consultar(filtros(Integer.MAX_VALUE, 100, Recepcion.TODOS, false));
        assertThat(fuera.contenido()).isEmpty();
        assertThat(fuera.pagina()).isEqualTo(Integer.MAX_VALUE);
        assertThat(fuera.totalElementos()).isEqualTo(3);
        assertThat(fuera.totalPaginas()).isEqualTo(1);

        when(locales.consultarBase(null, null, null)).thenReturn(List.of());
        clearInvocations(operacion);
        PaginaEntregasFallidas vacia = consulta.consultar(FiltrosConsultaFallidos.iniciales());
        assertThat(vacia.contenido()).isEmpty();
        assertThat(vacia.totalPaginas()).isZero();
        assertThat(vacia.indicadores()).isEqualTo(new PaginaEntregasFallidas.Indicadores(0, 0, 0L, 0));
        verifyNoInteractions(operacion);
    }

    @Test
    void unaJornadaDesconocidaFueraDePaginaDegradaElIndicadorYBloqueaSoloAtrasados() {
        when(locales.consultarBase(null, null, null)).thenReturn(List.of(
                fila("R-01", true, AHORA), fila("P-01", false, AHORA), fila("P-02", false, AHORA)));
        estados.put("JOR-P-01", EstadoJornada.CERRADA);
        nombresDisponibles = false;

        PaginaEntregasFallidas primera = consulta.consultar(filtros(1, 1, Recepcion.TODOS, false));

        assertThat(primera.indicadores()).isEqualTo(new PaginaEntregasFallidas.Indicadores(2, 1, null, 1));
        assertThat(primera.contenido().getFirst().retornoAtrasado()).isFalse();
        assertThat(primera.contenido().getFirst().repartidor().nombre()).isNull();
        PaginaEntregasFallidas desconocida = consulta.consultar(filtros(2, 1, Recepcion.PENDIENTE_RETORNO, false));
        assertThat(desconocida.totalElementos()).isEqualTo(2);
        assertThat(desconocida.contenido().getFirst().retornoAtrasado()).isNull();
        assertThat(desconocida.contenido().getFirst().jornada().estado()).isEqualTo(EstadoJornada.DESCONOCIDA);

        clearInvocations(operacion);
        assertThatThrownBy(() -> consulta.consultar(filtros(1, 20, Recepcion.TODOS, true)))
                .isInstanceOfSatisfying(ErrorDeNegocio.class, error -> {
                    assertThat(error.getEstado()).isEqualTo(503);
                    assertThat(error.getCodigo()).isEqualTo("DESP_ERROR_JORNADAS_NO_DISPONIBLES");
                });
        verify(operacion, times(1)).consultar(eq(List.of()), anyCollection());
    }

    @Test
    void filtraSoloAtrasadosConJornadasConfirmadasYNoDependeDeOperacionSiNoHayPendientes() {
        when(locales.consultarBase(null, null, null)).thenReturn(List.of(
                fila("P-01", false, AHORA), fila("P-02", false, AHORA), fila("R-01", true, AHORA)));
        estados.put("JOR-P-01", EstadoJornada.CERRADA);
        estados.put("JOR-P-02", EstadoJornada.ACTIVA);

        PaginaEntregasFallidas resultado = consulta.consultar(filtros(1, 20, Recepcion.PENDIENTE_RETORNO, true));

        assertThat(resultado.contenido()).extracting(PaginaEntregasFallidas.Fila::idDespacho).containsExactly("P-01");
        assertThat(resultado.totalElementos()).isEqualTo(1);
        assertThat(resultado.indicadores()).isEqualTo(new PaginaEntregasFallidas.Indicadores(2, 1, 1L, 0));
        when(locales.consultarBase(null, null, null)).thenReturn(List.of(fila("R-01", true, AHORA)));
        clearInvocations(operacion);
        PaginaEntregasFallidas sinPendientes = consulta.consultar(filtros(1, 20, Recepcion.TODOS, true));
        assertThat(sinPendientes.contenido()).isEmpty();
        assertThat(sinPendientes.indicadores()).isEqualTo(new PaginaEntregasFallidas.Indicadores(0, 1, 0L, 0));
        verifyNoInteractions(operacion);
    }

    @Test
    void rechazaFiltrosInvalidosYMotivoInexistenteAntesDeLeerIncidenciasODatosOperativos() {
        LocalDate dia = LocalDate.of(2026, 10, 16);
        List<Runnable> invalidos = List.of(
                () -> filtros(0, 20, Recepcion.TODOS, false),
                () -> filtros(1, 101, Recepcion.TODOS, false),
                () -> filtros(1, 20, Recepcion.RECIBIDO_EN_CENTRO, true),
                () -> new FiltrosConsultaFallidos(1, 20, " ", null, null, null, false),
                () -> new FiltrosConsultaFallidos(1, 20, null, dia, dia.minusDays(1), null, false));
        invalidos.forEach(invalido -> assertThatThrownBy(invalido::run)
                .isInstanceOfSatisfying(ErrorDeNegocio.class, error -> {
                    assertThat(error.getEstado()).isEqualTo(400);
                    assertThat(error.getCodigo()).isEqualTo("DESP_ERROR_FILTROS_INVALIDOS");
                    assertThat(error.getErrores()).isNotEmpty();
                }));
        verifyNoInteractions(locales, operacion, motivos);
        when(motivos.existeCodigo("NO_EXISTE")).thenReturn(false);
        assertThatThrownBy(() -> consulta.consultar(new FiltrosConsultaFallidos(1, 20, "NO_EXISTE", null, null, null, false)))
                .isInstanceOfSatisfying(ErrorDeNegocio.class, error -> assertThat(error.getEstado()).isEqualTo(400));
        verifyNoInteractions(locales, operacion);
    }

    @Test
    void delegaMotivoYFechasYConservaContadorYCondicionNoIntentado() {
        LocalDate desde = LocalDate.of(2026, 10, 14);
        LocalDate hasta = LocalDate.of(2026, 10, 16);
        when(locales.consultarBase("NO_INTENTADO", desde, hasta)).thenReturn(List.of(fila("P-01", false, AHORA)));
        estados.put("JOR-P-01", EstadoJornada.ACTIVA);

        PaginaEntregasFallidas resultado = consulta.consultar(new FiltrosConsultaFallidos(
                1, 20, "NO_INTENTADO", desde, hasta, Recepcion.TODOS, false));

        verify(motivos).existeCodigo("NO_INTENTADO");
        verify(locales).consultarBase("NO_INTENTADO", desde, hasta);
        assertThat(resultado.contenido().getFirst().numeroIntentos()).isEqualTo(1);
        assertThat(resultado.contenido().getFirst().maximoIntentos()).isEqualTo(2);
        assertThat(resultado.contenido().getFirst().consumeIntento()).isFalse();
        assertThat(resultado.contenido().getFirst().idIncidencia()).isEqualTo("INC-P-01");
        assertThat(resultado.contenido().getFirst().jornada().idJornada()).isEqualTo("JOR-P-01");
    }

    private static FiltrosConsultaFallidos filtros(int pagina, int tamano, Recepcion recepcion, boolean atrasados) {
        return new FiltrosConsultaFallidos(pagina, tamano, null, null, null, recepcion, atrasados);
    }

    private static IncidenciaListadoLocal fila(String id, boolean recibido, Instant fecha) {
        return new IncidenciaListadoLocal(id, "INC-" + id, 4, "TRK-" + id, fecha,
                new Motivo("NO_INTENTADO", "No intentado por fin de jornada"), 1, 2, false,
                "REP-" + id, "JOR-" + id, recibido, false, false);
    }
}
