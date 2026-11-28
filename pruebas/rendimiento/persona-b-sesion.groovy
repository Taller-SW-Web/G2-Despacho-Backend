import java.net.URI
import java.net.http.*
import groovy.json.JsonSlurper
def cotiza = props.getProperty('caso') == 'PERF-03'
def ruta = cotiza ? '/api/v1/auth/token' : '/pruebas/token-usuario'
def formulario = cotiza ?
    'grant_type=client_credentials&client_id=modulo-marketplace&client_secret=despacho-local-no-produccion&scope=cotizaciones%3Acalcular' :
    'perfil=gestor'
def solicitud = HttpRequest.newBuilder(URI.create(props.getProperty('seguridad','http://127.0.0.1:18090') + ruta))
    .timeout(java.time.Duration.ofSeconds(10))
    .header('Content-Type','application/x-www-form-urlencoded')
    .POST(HttpRequest.BodyPublishers.ofString(formulario)).build()
def r = HttpClient.newHttpClient().send(solicitud,HttpResponse.BodyHandlers.ofString())
if (r.statusCode() != 200) throw new IllegalStateException('No se obtuvo la credencial local')
props.put('tokenB',new JsonSlurper().parseText(r.body()).accessToken)
def control
def cuerpo
def rutaControl
if (cotiza) {
    rutaControl = '/api/v1/cotizaciones'
    cuerpo = [destino:[direccion:'Destino sintético Persona B',distrito:'Distrito Persona B'],
        lineas:[[sku:'POL-NEG-M',cantidad:5],[sku:'ZAP-RUN-42',cantidad:1]]]
} else {
    control = new JsonSlurper().parse(new File('datos/control-asignacion.json'))
    rutaControl = '/api/v1/despachos/' + control.idDespacho + '/asignacion'
    cuerpo = [idRepartidor:control.idRepartidor,idJornada:control.idJornada]
}
def base = props.getProperty('esquema','http') + '://' + props.getProperty('host','127.0.0.1') + ':' + props.getProperty('puerto','18080')
def preflight = HttpRequest.newBuilder(URI.create(base + rutaControl))
    .timeout(java.time.Duration.ofSeconds(10))
    .header('Authorization','Bearer ' + props.get('tokenB')).header('Content-Type','application/json')
    .POST(HttpRequest.BodyPublishers.ofString(groovy.json.JsonOutput.toJson(cuerpo))).build()
def verificada = HttpClient.newHttpClient().send(preflight,HttpResponse.BodyHandlers.ofString())
if (verificada.statusCode() != 200) throw new IllegalStateException('Control previo rechazado: HTTP '+verificada.statusCode())
def v = new JsonSlurper().parseText(verificada.body())
boolean correcto = cotiza ?
    v.tipoCotizacion == 'EXACTA' && v.pesoTotalKg == 2.65 && v.volumenTotalM3 == 0.021 && v.costoEnvio == 10 :
    v.estado == 'ASIGNADO' && v.idDespacho == control.idDespacho && v.idRepartidor == control.idRepartidor &&
    v.idJornada == control.idJornada && v.idReserva instanceof String && !v.idReserva.isBlank()
if (!correcto) throw new IllegalStateException('Control previo con datos incorrectos')
if (props.getProperty('control')) new File(props.getProperty('control')).text = groovy.json.JsonOutput.toJson(v)
// No guardar Bearer ni respuesta de Seguridad en el JTL.
SampleResult.setResponseMessage('Credencial local preparada')
return null
