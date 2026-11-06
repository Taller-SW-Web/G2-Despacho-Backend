param([System.Security.SecureString]$Token)

# Solo GET a los servicios publicados de G2 y al proveedor G7 acordado.
# No muestra JWT, cuerpos de respuesta ni datos del usuario o de los despachos.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
if (-not $Token) {
    $Token = Read-Host 'Pega el access token de la peticion fallida (entrada oculta)' -AsSecureString
}

$puntero = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Token)
try { $tokenPlano = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($puntero).Trim() }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($puntero) }
$tokenPlano = $tokenPlano -replace '(?i)^Bearer\s+', ''
if ($tokenPlano -notmatch '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$') {
    throw 'La entrada no tiene formato JWT. Copia el valor de Authorization de la peticion fallida.'
}

# Esta lectura de exp orienta el diagnostico; no verifica la firma.
$payload = $tokenPlano.Split('.')[1].Replace('-', '+').Replace('_', '/')
$payload = $payload.PadRight($payload.Length + ((4 - $payload.Length % 4) % 4), '=')
try { $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json }
catch { throw 'El payload del JWT no es JSON valido.' }
if ($claims.tipo -ne 'acceso') { throw 'Utiliza el access token del gestor, de tipo acceso.' }
if ($null -ne $claims.exp) {
    $vence = [DateTimeOffset]::FromUnixTimeSeconds([long]$claims.exp)
    Write-Output ('Vencimiento UTC declarado (sin verificar firma): ' + $vence.UtcDateTime.ToString('u'))
    Write-Output ('Vencido segun el reloj local: ' + ($vence -le [DateTimeOffset]::UtcNow))
}
$claims = $null
$payload = $null

$destinos = @(
    @{ nombre = 'G7: perfil'; url = 'https://g7-auth-service.onrender.com/api/v1/auth/me' },
    @{ nombre = 'Gateway: perfil'; url = 'https://taller-api-gateway.onrender.com/api/v1/auth/me' },
    @{ nombre = 'Gestion directa: cola'; url = 'https://taller-servicio-gestion-despachos.onrender.com/api/v1/despachos/pendientes?pagina=1&tamanoPagina=1' },
    @{ nombre = 'Gateway: cola'; url = 'https://taller-api-gateway.onrender.com/api/v1/despachos/pendientes?pagina=1&tamanoPagina=1' }
)
$manejador = New-Object System.Net.Http.HttpClientHandler
$manejador.AllowAutoRedirect = $false
$manejador.UseCookies = $false
$cliente = New-Object System.Net.Http.HttpClient -ArgumentList $manejador
$cliente.Timeout = [TimeSpan]::FromSeconds(15)
$cliente.MaxResponseContentBufferSize = 1048576
$cliente.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue -ArgumentList 'Bearer', $tokenPlano
$cliente.DefaultRequestHeaders.Accept.ParseAdd('application/json')
$tokenPlano = $null
try {
    $resultados = @(foreach ($destino in $destinos) {
        $respuesta = $null
        try {
            $respuesta = $cliente.GetAsync($destino.url).GetAwaiter().GetResult()
            $estado = [int]$respuesta.StatusCode
            $codigo = ''
            $correlacion = ''
            if ($estado -ge 400) {
                try {
                    $problema = $respuesta.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                    if ($problema.code -cmatch '^[A-Z0-9_]{1,100}$') { $codigo = [string]$problema.code }
                    if ($problema.correlationId -match '^[0-9a-fA-F-]{36}$') { $correlacion = [string]$problema.correlationId }
                } catch { $codigo = 'RESPUESTA_SIN_PROBLEMA_JSON' }
            }
            [pscustomobject]@{ Destino = $destino.nombre; HTTP = $estado; Codigo = $codigo; Correlacion = $correlacion }
        } catch {
            [pscustomobject]@{ Destino = $destino.nombre; HTTP = 0; Codigo = 'SIN_RESPUESTA_HTTP'; Correlacion = '' }
        } finally { if ($null -ne $respuesta) { $respuesta.Dispose() } }
    })
} finally { $cliente.Dispose() }
$resultados | Format-Table -AutoSize

$g7, $perfilGateway, $gestion, $colaGateway = $resultados
if ($g7.HTTP -eq 401) {
    Write-Output 'G7 rechaza esta sesion: obtener una sesion nueva y repetir antes de cambiar variables.'
} elseif ($g7.HTTP -eq 200 -and $perfilGateway.HTTP -eq 401) {
    Write-Output 'G7 acepta el token y el Gateway lo rechaza: revisar SEGURIDAD_URL, emisor y claves en el Gateway.'
} elseif ($g7.HTTP -eq 200 -and $gestion.HTTP -eq 401) {
    Write-Output 'G7 acepta el token y Gestion lo rechaza: revisar SEGURIDAD_URL, emisor y claves en Gestion.'
} elseif ($gestion.HTTP -eq 200 -and $perfilGateway.HTTP -eq 200 -and $colaGateway.HTTP -eq 401) {
    Write-Output 'La sesion es aceptada directamente: revisar GESTION_DESPACHOS_URL y el destino real de la ruta del Gateway.'
} elseif (@($resultados | Where-Object { $_.HTTP -ne 200 }).Count -eq 0) {
    Write-Output 'La sesion es aceptada en las cuatro consultas. El 401 original no se reproduce con este token.'
} elseif ($gestion.HTTP -eq 403 -or $colaGateway.HTTP -eq 403) {
    Write-Output 'Hay un rechazo de autorizacion: comprobar que la sesion tenga el rol GESTOR_DESPACHO.'
} else {
    Write-Output 'Resultado no concluyente. Compartir solo esta tabla, sin el token, para continuar el diagnostico.'
}
