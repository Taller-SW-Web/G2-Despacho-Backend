# Paquete de evidencia local · hito 5

- Archivo: `pruebas/rendimiento/resultados/entrega-hito5-java21.tar.gz`
- Tamaño: 7.118.743 bytes.
- SHA-256: `45e360fe80f8db598c6b87fc91756bbd05941e99edcf4c2d1671ca1281dd731a`
- Estado: **generado y verificado localmente; no publicado**.

Desde `pruebas/rendimiento/resultados`:

```bash
sha256sum -c entrega-hito5-java21.tar.gz.sha256
tar -xzf entrega-hito5-java21.tar.gz
cd entrega-hito5-java21
sha256sum -c SHA256SUMS
```

El manifiesto interno cubre los archivos contenidos antes de comprimir:

- Cinco corridas aprobadas: JTL, HTML completo, ficha, gráficas, métricas y trazabilidad.
- Entradas de carga sin credenciales y fuentes del commit medido `4a81f6d`.
- Surefire de los tres servicios y de integración sobre `4a81f6d` y `c9363fe`.
- Informes de INT-01/02/08/09 y comparación Python.
- Primer intento no concluyente de PERF-03 y logs de los intentos de INT-09.
- Regresiones de Seguridad/Ventas temporales, verificadores Python y herramientas de rendimiento.

Se comprobó la presencia de cada JTL/HTML y la ausencia de JWT completos en los
artefactos de texto. Los datos son sintéticos. No sustituye revisión humana.

Publicar el paquete en un destino autorizado y pegar el enlace en el PR sigue a cargo
del equipo. El archivo está ignorado por Git: hacer push de la rama **no lo sube**.
