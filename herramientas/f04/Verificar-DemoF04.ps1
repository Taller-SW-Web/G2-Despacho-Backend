# Requiere PowerShell 7 y el proyecto Compose g2-f04-demo iniciado según la guía.
[CmdletBinding()]
param([switch]$ComprobarCaida)
$ErrorActionPreference = 'Stop'
$raizF04 = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$composeF04 = @('--env-file', (Join-Path $raizF04 '.env.f04-demo.example'), '-p', 'g2-f04-demo',
    '-f', (Join-Path $raizF04 'compose.yaml'), '-f', (Join-Path $raizF04 'compose.seguridad-local.yaml'),
    '-f', (Join-Path $raizF04 'compose.f04.yaml'))

function Exigir-F04($condicion, [string]$mensaje) {
    if (!$condicion) { throw $mensaje }
}
function Origen-F04([string]$servicio, [int]$puerto) {
    $direccion = & docker compose @composeF04 port $servicio $puerto
    if ($LASTEXITCODE -ne 0) { throw "No se encontró $servicio en la demo aislada" }
    return 'http://' + $direccion.Trim()
}

# Las guardas SQL exigen los nombres de las bases exclusivas de esta demo.
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'datos-gestion.sql') -Raw -Encoding utf8 |
    & docker compose @composeF04 exec -T postgres-gestion-f04 psql -v ON_ERROR_STOP=1 -U despacho_demo -d f04_gestion_demo | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar la base de demo de Gestión' }
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'datos-operacion.sql') -Raw -Encoding utf8 |
    & docker compose @composeF04 exec -T postgres-operacion-f04 psql -v ON_ERROR_STOP=1 -U despacho_demo -d f04_operacion_demo | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar la base de demo de Operación' }

$gatewayF04 = Origen-F04 'api-gateway' 8080
$seguridadF04 = Origen-F04 'seguridad-local' 8090
$gestorF04 = Invoke-RestMethod -Method Post -Uri "$seguridadF04/pruebas/token-usuario" -Body @{ perfil='gestor' }
$otroF04 = Invoke-RestMethod -Method Post -Uri "$seguridadF04/pruebas/token-usuario" -Body @{ perfil='sin-rol' }
$encabezadosF04 = @{ Authorization='Bearer ' + $gestorF04.accessToken }
$rutaF04 = "$gatewayF04/api/v1/despachos/fallidos"
$paginaF04 = Invoke-RestMethod -Uri "${rutaF04}?pagina=3&tamanoPagina=2" -Headers $encabezadosF04
Exigir-F04 ($paginaF04.totalElementos -eq 12 -and $paginaF04.contenido.Count -eq 2) 'Listado o paginación incorrectos'
Exigir-F04 ($paginaF04.indicadores.pendientesRetorno -eq 7 -and $paginaF04.indicadores.recibidosCentro -eq 5 -and
    $paginaF04.indicadores.retornosAtrasados -eq 2) 'Los indicadores deben ser 7/5/2 con Operación real'
Exigir-F04 (![string]::IsNullOrWhiteSpace($paginaF04.contenido[1].repartidor.nombre)) 'Falta el nombre provisto por Operación'
$catalogoF04 = Invoke-RestMethod -Uri "$gatewayF04/api/v1/catalogos/motivos-fallo" -Headers $encabezadosF04
Exigir-F04 ($catalogoF04.motivos.Count -eq 7) 'Faltan motivos de la migración canónica'
$atrasadosF04 = Invoke-RestMethod -Uri "${rutaF04}?soloRetornosAtrasados=true" -Headers $encabezadosF04
Exigir-F04 ($atrasadosF04.totalElementos -eq 2) 'El filtro de atrasados debe devolver dos filas'
$anonimoF04 = Invoke-WebRequest -Uri $rutaF04 -SkipHttpErrorCheck
$denegadoF04 = Invoke-WebRequest -Uri $rutaF04 -Headers @{ Authorization='Bearer ' + $otroF04.accessToken } -SkipHttpErrorCheck
$invalidoF04 = Invoke-WebRequest -Uri "${rutaF04}?pagina=0" -Headers $encabezadosF04 -SkipHttpErrorCheck
$internoF04 = Invoke-WebRequest -Uri "$gatewayF04/internal/v1/operacion/consulta-incidencias" -Method Post -Headers $encabezadosF04 -SkipHttpErrorCheck
Exigir-F04 ($anonimoF04.StatusCode -eq 401 -and $denegadoF04.StatusCode -eq 403 -and
    $invalidoF04.StatusCode -eq 400 -and $internoF04.StatusCode -eq 403) 'Permisos, validación o aislamiento de rutas incorrectos'

if ($ComprobarCaida) {
    & docker compose @composeF04 stop servicio-operacion-reparto | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo detener Operación de la demo aislada' }
    try {
        $degradadoF04 = Invoke-RestMethod -Uri $rutaF04 -Headers $encabezadosF04
        $soloF04 = Invoke-WebRequest -Uri "${rutaF04}?soloRetornosAtrasados=true" -Headers $encabezadosF04 -SkipHttpErrorCheck
        Exigir-F04 ($degradadoF04.totalElementos -eq 12 -and $null -eq $degradadoF04.indicadores.retornosAtrasados -and
            $soloF04.StatusCode -eq 503) 'La caída de Operación no conserva la degradación contractual'
    } finally {
        & docker compose @composeF04 start servicio-operacion-reparto | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo reiniciar Operación de la demo' }
        $operacionF04 = Origen-F04 'servicio-operacion-reparto' 8082
        $recuperadaF04 = $false
        for ($intentoF04 = 0; $intentoF04 -lt 30; $intentoF04++) {
            try {
                $saludF04 = Invoke-RestMethod -Uri "$operacionF04/actuator/health" -TimeoutSec 2
                if ($saludF04.status -eq 'UP') { $recuperadaF04 = $true; break }
            } catch { }
            Start-Sleep -Seconds 1
        }
        Exigir-F04 $recuperadaF04 'Operación no recuperó su disponibilidad después de reiniciar'
    }
}

# No imprimir JWT, secretos ni datos personales en los reportes.
[pscustomobject]@{ resultado='Aprobado'; entorno='Demo local aislada; Seguridad simulada con JWT firmado';
    servicios='Gateway, Gestión y Operación reales'; total=12; pendientes=7; recibidos=5; atrasados=2;
    catalogo=7; permisos='401/403'; filtrosInvalidos=400; rutasInternas='No expuestas';
    caidaOperacionComprobada=$ComprobarCaida.IsPresent } | ConvertTo-Json
