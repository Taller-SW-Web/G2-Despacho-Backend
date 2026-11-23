package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure.CatalogoProductosStub;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure.CatalogoProductosStub.ProductoStub;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionTarifas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionZonas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ProveedorUsuario;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ResolucionZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.TarifaAplicada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.TarifaVista;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.StandardEnvironment;

class ServicioCotizacionDoblesTests {

    private static final String SKU = "CUADERNO-01";
    private static final Instant AHORA = Instant.parse("2026-11-20T15:00:00Z");
    private static final BigDecimal COSTO_BASE = new BigDecimal("10.00");
    private static final BigDecimal COSTO_CINCO_UNIDADES = new BigDecimal("14.50");
    private static final BigDecimal DIMENSION_CM = BigDecimal.TEN;
    private static final UUID ID_ZONA = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final ResolucionZona COBERTURA = ResolucionZona.cubierto(ID_ZONA, "Lima Centro");
    private static final TarifaAplicada TARIFA =
            new TarifaAplicada(
                    UUID.fromString("22222222-2222-4222-8222-222222222222"),
                    ID_ZONA,
                    new TarifaVista(
                            COSTO_BASE,
                            BigDecimal.valueOf(3),
                            BigDecimal.valueOf(2),
                            new BigDecimal("1.25"),
                            "PEN",
                            2,
                            AHORA));
    private static final SolicitudCotizacion.Destino DESTINO =
            new SolicitudCotizacion.Destino("Av. de prueba 100", "Lince", null);
    private static final Map<String, DatosFisicos> PRODUCTO_INACTIVO =
            Map.of(
                    SKU,
                    new DatosFisicos(
                            BigDecimal.ONE,
                            DIMENSION_CM,
                            DIMENSION_CM,
                            DIMENSION_CM,
                            "INACTIVO",
                            AHORA));

    @Nested
    class DoblesManuales {

        @Test
        void stubRechazaProductoInactivo() {
            ProveedorDatosFisicos productos = skus -> PRODUCTO_INACTIVO;
            RegistroCotizaciones registro =
                    instantanea -> fail("La cotización rechazada se guardó");
            var servicio = crearServicioManual(productos, registro);

            var error = assertThrows(ErrorDeNegocio.class, () -> servicio.cotizar(solicitud(1)));

            assertEquals(CodigosError.PRODUCTO_NO_ELEGIBLE, error.getCodigo());
            assertEquals(List.of(SKU), error.getSkusAfectados());
        }

        @Test
        void mockGuardaUnaVezConElImporteEsperado() {
            var registro = new RegistroConExpectativa(COSTO_BASE);
            var servicio = crearServicioManual(catalogoLocal(), registro);

            servicio.cotizar(solicitud(1));

            registro.verificar();
        }

        @Test
        void fakeConservaDosCotizacionesEnElRegistro() {
            var registro = new RegistroEnMemoria();
            var servicio = crearServicioManual(catalogoLocal(), registro);

            servicio.cotizar(solicitud(1));
            servicio.cotizar(solicitud(5));

            comprobarImportesGuardados(registro.guardadas);
        }

        @Test
        void spyAgrupaLineasDelMismoSku() {
            var productos = new CatalogoEspia(catalogoLocal());
            var servicio = crearServicioManual(productos, new RegistroEnMemoria());

            var cotizacion = servicio.cotizar(solicitud(2, 3));

            assertEquals(List.of(List.of(SKU)), productos.consultas);
            assertEquals(BigDecimal.valueOf(5), cotizacion.pesoTotalKg());
        }
    }

    @Nested
    class ConMockito {

        @Test
        void stubRechazaProductoInactivo() {
            var productos = mock(ProveedorDatosFisicos.class);
            when(productos.obtener(anyCollection())).thenReturn(PRODUCTO_INACTIVO);
            var registro = mock(RegistroCotizaciones.class);
            var servicio = crearServicioConMockito(productos, registro);

            var error = assertThrows(ErrorDeNegocio.class, () -> servicio.cotizar(solicitud(1)));

            assertEquals(CodigosError.PRODUCTO_NO_ELEGIBLE, error.getCodigo());
            assertEquals(List.of(SKU), error.getSkusAfectados());
            verifyNoInteractions(registro);
        }

        @Test
        void mockGuardaUnaVezConElImporteEsperado() {
            var registro = mock(RegistroCotizaciones.class);
            var servicio = crearServicioConMockito(catalogoLocal(), registro);

            servicio.cotizar(solicitud(1));

            var guardada = ArgumentCaptor.forClass(InstantaneaCotizacion.class);
            verify(registro).guardar(guardada.capture());
            assertEquals(ID_ZONA, guardada.getValue().idZona());
            assertEquals(COSTO_BASE, guardada.getValue().resultado().costoEnvio());
        }

        @Test
        void fakeConservaDosCotizacionesEnElRegistro() {
            var guardadas = new ArrayList<InstantaneaCotizacion>();
            var registro = mock(RegistroCotizaciones.class);
            // Mockito ejecuta la respuesta configurada y la lista conserva el estado del fake.
            doAnswer(
                            invocacion -> {
                                guardadas.add(invocacion.getArgument(0));
                                return null;
                            })
                    .when(registro)
                    .guardar(any(InstantaneaCotizacion.class));
            var servicio = crearServicioConMockito(catalogoLocal(), registro);

            servicio.cotizar(solicitud(1));
            servicio.cotizar(solicitud(5));

            comprobarImportesGuardados(guardadas);
        }

        @Test
        void spyAgrupaLineasDelMismoSku() {
            var productos = spy(catalogoLocal());
            var registro = mock(RegistroCotizaciones.class);
            var servicio = crearServicioConMockito(productos, registro);

            var cotizacion = servicio.cotizar(solicitud(2, 3));

            verify(productos).obtener(Set.of(SKU));
            verifyNoMoreInteractions(productos);
            assertEquals(BigDecimal.valueOf(5), cotizacion.pesoTotalKg());
        }
    }

    private static final class RegistroConExpectativa implements RegistroCotizaciones {
        private final BigDecimal importeEsperado;
        private int llamadas;

        private RegistroConExpectativa(BigDecimal importeEsperado) {
            this.importeEsperado = importeEsperado;
        }

        @Override
        public void guardar(InstantaneaCotizacion instantanea) {
            llamadas++;
            assertEquals(ID_ZONA, instantanea.idZona());
            assertEquals(importeEsperado, instantanea.resultado().costoEnvio());
        }

        private void verificar() {
            assertEquals(1, llamadas);
        }
    }

    private static final class RegistroEnMemoria implements RegistroCotizaciones {
        private final List<InstantaneaCotizacion> guardadas = new ArrayList<>();

        @Override
        public void guardar(InstantaneaCotizacion instantanea) {
            guardadas.add(instantanea);
        }
    }

    private static final class CatalogoEspia implements ProveedorDatosFisicos {
        private final ProveedorDatosFisicos catalogo;
        private final List<List<String>> consultas = new ArrayList<>();

        private CatalogoEspia(ProveedorDatosFisicos catalogo) {
            this.catalogo = catalogo;
        }

        @Override
        public Map<String, DatosFisicos> obtener(Collection<String> skus) {
            consultas.add(List.copyOf(skus));
            return catalogo.obtener(skus);
        }
    }

    private static ServicioCotizacion crearServicioManual(
            ProveedorDatosFisicos productos, RegistroCotizaciones registro) {
        // Los métodos sobrescritos devuelven datos fijos sin usar los repositorios de zonas y
        // tarifas.
        var zonas =
                new GestionZonas(null, null, null, null, null, null, null) {
                    @Override
                    public ResolucionZona resolverZona(String distrito, String codigoPostal) {
                        return COBERTURA;
                    }
                };
        var tarifas =
                new GestionTarifas(null, null, null, null, null) {
                    @Override
                    public Optional<TarifaAplicada> capturar(UUID idZona) {
                        return Optional.of(TARIFA);
                    }
                };
        return crearServicio(zonas, tarifas, productos, registro);
    }

    private static ServicioCotizacion crearServicioConMockito(
            ProveedorDatosFisicos productos, RegistroCotizaciones registro) {
        var zonas = mock(GestionZonas.class);
        when(zonas.resolverZona(any(), any())).thenReturn(COBERTURA);
        var tarifas = mock(GestionTarifas.class);
        when(tarifas.capturar(any())).thenReturn(Optional.of(TARIFA));
        return crearServicio(zonas, tarifas, productos, registro);
    }

    private static ServicioCotizacion crearServicio(
            GestionZonas zonas,
            GestionTarifas tarifas,
            ProveedorDatosFisicos productos,
            RegistroCotizaciones registro) {
        ProveedorUsuario consumidor =
                new ProveedorUsuario() {
                    @Override
                    public String identificador() {
                        return "canal-pruebas";
                    }

                    @Override
                    public Optional<UUID> idUuid() {
                        return Optional.empty();
                    }
                };
        var entorno = new StandardEnvironment();
        entorno.setActiveProfiles("pruebas");
        return new ServicioCotizacion(
                zonas,
                tarifas,
                productos,
                registro,
                consumidor,
                Clock.fixed(AHORA, ZoneOffset.UTC),
                entorno);
    }

    private static CatalogoProductosStub catalogoLocal() {
        var catalogo = new CatalogoProductosStub();
        catalogo.getCatalogo()
                .put(
                        SKU,
                        new ProductoStub(BigDecimal.ONE, DIMENSION_CM, DIMENSION_CM, DIMENSION_CM));
        return catalogo;
    }

    private static SolicitudCotizacion solicitud(Integer... cantidades) {
        var lineas =
                List.of(cantidades).stream()
                        .map(cantidad -> new SolicitudCotizacion.Linea(SKU, cantidad))
                        .toList();
        return new SolicitudCotizacion(DESTINO, lineas);
    }

    private static void comprobarImportesGuardados(List<InstantaneaCotizacion> guardadas) {
        var importes =
                guardadas.stream().map(guardada -> guardada.resultado().costoEnvio()).toList();
        assertEquals(List.of(COSTO_BASE, COSTO_CINCO_UNIDADES), importes);
    }
}
