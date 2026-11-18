package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application.RepositorioGeometrias;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Profile("!integracion-local")
public class GeometriasAdaptadorJdbc implements RepositorioGeometrias {
    private final JdbcClient sql;
    private final JsonMapper json;

    public GeometriasAdaptadorJdbc(JdbcClient sql, JsonMapper json) {
        this.sql = sql;
        this.json = json;
    }

    @Override
    public List<DistritoGeografico> catalogo(String nombre, String estado, UUID zona) {
        return sql.sql(
                        """
                WITH asociaciones AS (
                    SELECT zona_id,distrito_id,false postal FROM zonas_distritos
                    UNION ALL SELECT zona_id,distrito_id,true postal FROM zonas_codigos_postales
                ), agrupadas AS (
                    SELECT zona_id,distrito_id,bool_and(postal) postal
                    FROM asociaciones GROUP BY zona_id,distrito_id
                )
                SELECT d.id,d.version,jsonb_build_object('type','Feature',
                    'geometry',CASE WHEN d.geom IS NULL THEN NULL ELSE extensions.ST_AsGeoJSON(d.geom,15)::jsonb END,
                    'properties',jsonb_build_object(
                        'id',d.id,'nombre',d.nombre,'provincia',d.provincia,'departamento',d.departamento,
                        'ubigeo',d.ubigeo,'estado',d.estado,'versionGeometria',coalesce(g.version,0),
                        'fuente',g.fuente,'versionFuente',g.version_fuente,'importadoEn',g.importado_en,
                        'importadoPor',g.importado_por,'huella',g.huella,'importacionId',g.importacion_id,'nombreArchivo',i.nombre_archivo,
                        'zonas',coalesce((SELECT jsonb_agg(jsonb_build_object('id',z.id,'nombre',z.nombre,
                            'estado',z.estado,'soloCodigosPostales',a.postal) ORDER BY z.id)
                            FROM agrupadas a JOIN zonas z ON z.id=a.zona_id WHERE a.distrito_id=d.id),'[]'::jsonb))
                        || CASE WHEN CAST(:zona AS uuid) IS NULL THEN '{}'::jsonb ELSE
                            jsonb_build_object('soloCodigosPostales',(SELECT a.postal FROM agrupadas a
                                WHERE a.distrito_id=d.id AND a.zona_id=CAST(:zona AS uuid))) END)::text entidad
                FROM distrito d LEFT JOIN geometrias_distrito g ON g.distrito_id=d.id
                LEFT JOIN importaciones_geograficas i ON i.id=g.importacion_id
                WHERE (:nombre='' OR position(lower(:nombre) in lower(d.nombre))>0)
                  AND (:estado='TODOS' OR d.estado=:estado)
                  AND (CAST(:zona AS uuid) IS NULL OR EXISTS(SELECT 1 FROM agrupadas a
                    WHERE a.distrito_id=d.id AND a.zona_id=CAST(:zona AS uuid)))
                ORDER BY d.ubigeo,d.id
                """)
                .param("nombre", nombre == null ? "" : nombre.trim())
                .param("estado", estado)
                .param("zona", zona, java.sql.Types.OTHER)
                .query(
                        (fila, indice) ->
                                new DistritoGeografico(
                                        fila.getObject("id", UUID.class),
                                        fila.getLong("version"),
                                        json.readTree(fila.getString("entidad"))))
                .list();
    }

    @Override
    public Optional<JsonNode> zona(UUID id) {
        return sql.sql(
                        "SELECT jsonb_build_object('id',id,'nombre',nombre,'estado',estado)::text FROM zonas WHERE id=:id")
                .param("id", id)
                .query((fila, indice) -> json.readTree(fila.getString(1)))
                .optional();
    }

    @Override
    public String motivoInvalidez(JsonNode geometria) {
        return sql.sql(
                        """
                WITH dato AS (SELECT extensions.ST_SetSRID(extensions.ST_GeomFromGeoJSON(:geo),4326) g)
                SELECT CASE WHEN extensions.ST_IsValid(g) AND NOT extensions.ST_IsEmpty(g)
                  THEN '' ELSE extensions.ST_IsValidReason(g) END FROM dato
                """)
                .param("geo", json.writeValueAsString(geometria))
                .query(String.class)
                .single();
    }

    @Override
    public void guardarValidacion(UUID id, String ejecutor, Instant expiraEn, JsonNode propuesta) {
        sql.sql(
                        "INSERT INTO validaciones_geograficas(id,ejecutor_id,expira_en,propuesta) VALUES (:id,:ejecutor,:vence,CAST(:propuesta AS jsonb))")
                .param("id", id)
                .param("ejecutor", ejecutor)
                .param("vence", Timestamp.from(expiraEn))
                .param("propuesta", json.writeValueAsString(propuesta))
                .update();
    }

    @Override
    public Optional<Validacion> buscarValidacion(UUID id, String ejecutor) {
        return sql.sql(
                        "SELECT v.id,v.expira_en,v.propuesta::text,EXISTS(SELECT 1 FROM importaciones_geograficas i WHERE i.validacion_id=v.id) consumida FROM validaciones_geograficas v WHERE v.id=:id AND v.ejecutor_id=:ejecutor")
                .param("id", id)
                .param("ejecutor", ejecutor)
                .query(
                        (fila, indice) ->
                                new Validacion(
                                        fila.getObject(1, UUID.class),
                                        fila.getTimestamp(2).toInstant(),
                                        json.readTree(fila.getString(3)),
                                        fila.getBoolean(4)))
                .optional();
    }

    @Override
    public void registrarImportacion(
            UUID id,
            UUID validacion,
            String fuente,
            String versionFuente,
            String nombreArchivo,
            String ejecutor,
            Instant ahora) {
        sql.sql(
                        "INSERT INTO importaciones_geograficas(id,validacion_id,fuente,version_fuente,nombre_archivo,ejecutor_id,importado_en) VALUES (:id,:validacion,:fuente,:version,:archivo,:ejecutor,:ahora)")
                .param("id", id)
                .param("validacion", validacion)
                .param("fuente", fuente)
                .param("version", versionFuente)
                .param("archivo", nombreArchivo, java.sql.Types.VARCHAR)
                .param("ejecutor", ejecutor)
                .param("ahora", Timestamp.from(ahora))
                .update();
    }

    @Override
    public void guardarGeometria(
            UUID distrito,
            JsonNode geometria,
            long version,
            String fuente,
            String versionFuente,
            UUID importacion,
            String ejecutor,
            Instant ahora,
            String huella) {
        sql.sql(
                        """
                INSERT INTO historico_geometrias(distrito_id,version,geom,metadatos,sustituido_en,sustituido_por,importacion_id)
                SELECT d.id,coalesce(g.version,0),d.geom,to_jsonb(g),:ahora,:ejecutor,:importacion
                FROM distrito d LEFT JOIN geometrias_distrito g ON g.distrito_id=d.id
                WHERE d.id=:distrito AND d.geom IS NOT NULL
                """)
                .param("distrito", distrito)
                .param("ahora", Timestamp.from(ahora))
                .param("ejecutor", ejecutor)
                .param("importacion", importacion)
                .update();
        sql.sql(
                        "UPDATE distrito SET geom=extensions.ST_SetSRID(extensions.ST_GeomFromGeoJSON(:geo),4326) WHERE id=:id")
                .param("geo", json.writeValueAsString(geometria))
                .param("id", distrito)
                .update();
        sql.sql(
                        """
                INSERT INTO geometrias_distrito(distrito_id,version,fuente,version_fuente,importado_por,importado_en,huella,importacion_id)
                VALUES (:distrito,:version,:fuente,:revision,:ejecutor,:ahora,:huella,:importacion)
                ON CONFLICT (distrito_id) DO UPDATE SET version=excluded.version,fuente=excluded.fuente,
                    version_fuente=excluded.version_fuente,importado_por=excluded.importado_por,
                    importado_en=excluded.importado_en,huella=excluded.huella,importacion_id=excluded.importacion_id
                """)
                .param("distrito", distrito)
                .param("version", version)
                .param("fuente", fuente)
                .param("revision", versionFuente)
                .param("ejecutor", ejecutor)
                .param("ahora", Timestamp.from(ahora))
                .param("huella", huella)
                .param("importacion", importacion)
                .update();
    }
}
