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
// No guardar Bearer ni respuesta de Seguridad en el JTL.
