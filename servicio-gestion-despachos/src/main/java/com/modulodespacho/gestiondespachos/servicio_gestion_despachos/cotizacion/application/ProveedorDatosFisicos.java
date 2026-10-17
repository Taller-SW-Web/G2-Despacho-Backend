package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import java.util.Collection;
import java.util.Map;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;

/** Puerto hacia Productos y Ofertas para obtener el peso y las dimensiones vigentes. */
public interface ProveedorDatosFisicos {

    /**
     * Devuelve los datos físicos de los SKU indicados. Los SKU ausentes en el mapa se consideran
     * no reconocidos. Si la consulta no puede completarse, lanza un error de negocio 503.
     */
    Map<String, DatosFisicos> obtener(Collection<String> skus);
}
