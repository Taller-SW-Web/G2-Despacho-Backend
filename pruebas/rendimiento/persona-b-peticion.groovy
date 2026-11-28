import groovy.json.JsonOutput
if (props.getProperty('caso') == 'PERF-03') {
    vars.put('rutaB','/api/v1/cotizaciones')
    vars.put('cuerpoB',JsonOutput.toJson([
        destino:[direccion:'Destino sintético Persona B',distrito:'Distrito Persona B'],
        lineas:[[sku:'POL-NEG-M',cantidad:5],[sku:'ZAP-RUN-42',cantidad:1]]]))
} else {
    vars.put('rutaB','/api/v1/despachos/' + vars.get('idDespacho') + '/asignacion')
    vars.put('cuerpoB',JsonOutput.toJson([idRepartidor:vars.get('idRepartidor'),idJornada:vars.get('idJornada')]))
}
