import groovy.json.JsonOutput
def cotiza = props.getProperty('caso') == 'PERF-03'
def etiqueta = cotiza ? 'COTIZACION' : 'ASIGNACION'
double intervalo = cotiza ? 2000d : 500d
synchronized (props) {
    long ahora = System.currentTimeMillis()
    props.putIfAbsent('inicioB',ahora)
    long inicio = props.get('inicioB') as long
    if (props.putIfAbsent('ventanasB',true) == null) {
        new File(props.getProperty('ventanas')).text = JsonOutput.toJson([
            (etiqueta+'-CALENTAMIENTO'):[inicioMs:inicio,duracionS:60],
            (etiqueta+'-CARGA'):[inicioMs:inicio+60000,duracionS:300]])
    }
    long turno = Math.max((props.get('siguienteB') ?: inicio) as long,ahora)
    props.put('siguienteB',turno+(long)intervalo)
    vars.put('faseB',etiqueta+(turno-inicio < 60000 ? '-CALENTAMIENTO' : '-CARGA'))
    return Math.max(0L,turno-ahora)
}
