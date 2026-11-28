import groovy.json.JsonSlurper
if (prev.getResponseCode() != '200') {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage('HTTP_' + prev.getResponseCode())
    return
}
try {
    def r = new JsonSlurper().parseText(prev.getResponseDataAsString())
    boolean correcto
    if (props.getProperty('caso') == 'PERF-03') {
        correcto = r.tipoCotizacion == 'EXACTA' && r.coberturaDisponible == true &&
            r.pesoTotalKg == 2.65 && r.volumenTotalM3 == 0.021 && r.costoEnvio == 10 && r.moneda == 'PEN'
    } else {
        correcto = r.estado == 'ASIGNADO' && r.idDespacho == vars.get('idDespacho') &&
            r.idRepartidor == vars.get('idRepartidor') && r.idJornada == vars.get('idJornada') &&
            r.idReserva instanceof String && !r.idReserva.isBlank()
    }
    if (!correcto) throw new IllegalArgumentException('Respuesta incoherente')
    // Ledger de éxitos para reconciliar luego fuera del intervalo medido; nunca lleva tokens.
    synchronized (props) {
        new File(props.getProperty('ledger')).append(
            groovy.json.JsonOutput.toJson([fase:vars.get('faseB'),respuesta:r]) + '\n','UTF-8')
    }
} catch (Exception ignorada) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage('RESPUESTA_INVALIDA_O_NEGOCIO')
}
