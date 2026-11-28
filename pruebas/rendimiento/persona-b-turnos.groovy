import groovy.json.JsonOutput
def cotiza = props.getProperty('caso') == 'PERF-03'
def etiqueta = cotiza ? 'COTIZACION' : 'ASIGNACION'
double tasa = props.getProperty('tasa',cotiza ? '0.5' : '2') as double
long calentamiento = props.getProperty('calentamiento','60') as long
long duracion = props.getProperty('duracion','300') as long
if (tasa <= 0 || calentamiento < 0 || duracion <= 0) throw new IllegalArgumentException('Carga inválida')
double intervalo = 1000d / tasa
synchronized (props) {
    long ahora = System.currentTimeMillis()
    props.putIfAbsent('inicioB',ahora)
    long inicio = props.get('inicioB') as long
    if (props.putIfAbsent('ventanasB',true) == null) {
        new File(props.getProperty('ventanas')).text = JsonOutput.toJson([
            (etiqueta+'-CALENTAMIENTO'):[inicioMs:inicio,duracionS:calentamiento],
            (etiqueta+'-CARGA'):[inicioMs:inicio+calentamiento*1000,duracionS:duracion]])
    }
    long turno = Math.max((props.get('siguienteB') ?: inicio) as long,ahora)
    props.put('siguienteB',turno+(long)intervalo)
    vars.put('faseB',etiqueta+(turno-inicio < calentamiento*1000 ? '-CALENTAMIENTO' : '-CARGA'))
    return Math.max(0L,turno-ahora)
}
