package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioDistritos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoDistrito;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!integracion-local")
public class DistritosAdaptadorJpa implements RepositorioDistritos {

    private final DistritoRepositorioJpa distritos;
    private final jakarta.persistence.EntityManager entidades;

    public DistritosAdaptadorJpa(
            DistritoRepositorioJpa distritos, jakarta.persistence.EntityManager entidades) {
        this.distritos = distritos;
        this.entidades = entidades;
    }

    @Override
    public Map<String, Distrito> porNombres(Collection<String> nombres) {
        if (nombres == null || nombres.isEmpty()) {
            return Map.of();
        }
        List<String> normalizados =
                nombres.stream()
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
    public List<Distrito> catalogo(String nombre, EstadoDistrito estado) {
        String filtro = (nombre == null || nombre.isBlank()) ? null : nombre.trim();
        return distritos.catalogo(filtro, estado);
    }

    @Override
    public Optional<Distrito> buscarPorId(UUID id) {
        return distritos.findById(id);
    }

    @Override
    public Distrito guardar(Distrito distrito) {
        Distrito guardado = distritos.saveAndFlush(distrito);
        // PostgreSQL fija actualizado_en mediante trigger y persiste microsegundos.
        // Respuesta y auditoría deben utilizar exactamente la fotografía confirmada.
        entidades.refresh(guardado);
        return guardado;
    }

    @Override
    public boolean tieneAsociaciones(UUID id) {
        return distritos.tieneAsociaciones(id);
    }

    @Override
    public boolean existeNombre(String nombre, UUID excluirId) {
        return distritos.existeNombre(nombre, excluirId);
    }

    @Override
    public boolean existeUbigeo(String ubigeo, UUID excluirId) {
        return distritos.existeUbigeo(ubigeo, excluirId);
    }
}
