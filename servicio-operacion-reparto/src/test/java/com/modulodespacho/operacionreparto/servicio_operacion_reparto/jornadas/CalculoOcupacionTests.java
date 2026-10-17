package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import static org.assertj.core.api.Assertions.*;

class CalculoOcupacionTests {
    @ParameterizedTest
    @CsvSource({"499.999,3.9999,79,false,DISPONIBLE", "100,1,20,true,EN_RUTA", "500,1,20,true,SATURADO",
            "100,4,20,true,SATURADO", "100,1,80,false,SATURADO", "501,1,20,false,SATURADO"})
    void laSaturacionTienePrioridadEnCualquierDimensionSinRedondear(String peso,String volumen,int paquetes,boolean enCamino,String esperado) {
        var jornada=jornada(new Capacidad(new BigDecimal(peso),new BigDecimal(volumen),paquetes),enCamino,"ACTIVA");
        assertThat(jornada.estadoOperativo()).isEqualTo(esperado);
        assertThat(jornada.vista().capacidadRemanente().pesoKg()).isEqualByComparingTo(new BigDecimal("500").subtract(new BigDecimal(peso)));
    }
    @Test void unaJornadaCerradaConRetornosNoSeReabrePorTenerCapacidad() {
        var jornada=jornada(new Capacidad(new BigDecimal("100"),BigDecimal.ONE,20),false,"CERRADA");
        assertThat(jornada.estadoOperativo()).isEqualTo("FUERA_DE_TURNO");
        assertThat(jornada.vista().ocupacion().pesoKg()).isEqualByComparingTo("100");
    }
    @Test void conservaPrecisionYComparaCargasSinConfundirLaEscalaDecimal() {
        Capacidad capacidad=new Capacidad(new BigDecimal("500.000"),new BigDecimal("4.0000"),80);
        assertThat(capacidad.mismaCarga(new Capacidad(new BigDecimal("500"),new BigDecimal("4"),80))).isTrue();
        assertThat(capacidad.menos(new Capacidad(new BigDecimal("0.001"),new BigDecimal("0.0001"),1)))
                .isEqualTo(new Capacidad(new BigDecimal("499.999"),new BigDecimal("3.9999"),79));
    }
    private JornadaBase jornada(Capacidad ocupacion,boolean enCamino,String estado) {
        return new JornadaBase(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"TEST-01",UUID.randomUUID(),
                LocalDate.of(2026,10,17),estado,null,new Capacidad(new BigDecimal("500"),new BigDecimal("4"),80),ocupacion,enCamino,enCamino);
    }
}
