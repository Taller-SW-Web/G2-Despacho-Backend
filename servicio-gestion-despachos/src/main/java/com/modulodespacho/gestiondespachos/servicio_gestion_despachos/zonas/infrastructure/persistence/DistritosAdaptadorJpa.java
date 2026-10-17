package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioDistritos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;

@Component
@Profile("!integracion-local")
public class DistritosAdaptadorJpa implements RepositorioDistritos {

    private final DistritoRepositorioJpa distritos;

    public DistritosAdaptadorJpa(DistritoRepositorioJpa distritos) {
        this.distritos = distritos;
    }

    @Override
    public Map<String, Distrito> porNombres(Collection<String> nombres) {
        if (nombres == null || nombres.isEmpty()) {
            return Map.of();
        }
        List<String> normalizados = nombres.stream()
                .filter(nombre -> nombre != null && !nombre.isBlank())
                .map(nombre -> nombre.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        Map<String, Distrito> encontrados = new LinkedHashMap<>();
        for (Distrito distrito : distritos.buscarPorNombres(normalizados)) {
            encontrados.putIfAbsent(distrito.getNombre().toLowerCase(Locale.ROOT), distrito);
        }
        return encontrados;
    }

    @Override
    public List<Distrito> catalogo(String nombre) {
        String filtro = (nombre == null || nombre.isBlank()) ? null : nombre.trim();
        return distritos.catalogo(filtro);
    }
}
