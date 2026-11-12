package com.growlink.cursos.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

// De que IP es una peticion, para el tope por IP (ver LimitePorIpFilter.ipDe). Lo delicado es el puerto: Azure App
// Service manda el cliente como IP:puerto, y el puerto cambia con cada conexion. Si quedara pegado a la IP, cada
// conexion contaria como una IP nueva y el tope no frenaria a nadie (eso lo prueba LimitePorIpTest con el servicio
// levantado); aqui se prueban una por una las formas en que puede llegar la cabecera.
class IpDelClienteTest {

    @ParameterizedTest(name = "X-Forwarded-For \"{0}\" es la IP {1}")
    @CsvSource(delimiter = '|', value = {
            // IPv4, con y sin el puerto que agrega el balanceador
            "203.0.113.7                       | 203.0.113.7",
            "203.0.113.7:51234                 | 203.0.113.7",
            // IPv6 con puerto: va entre corchetes, y lo que cuenta es lo de adentro
            "[2001:db8::1]:51234               | 2001:db8::1",
            "[2001:db8::1]                     | 2001:db8::1",
            // IPv6 sin corchetes: sus ':' son de la direccion, no hay puerto que quitar
            "2001:db8::1                       | 2001:db8::1",
            "::1                               | ::1",
            // varios saltos: cuenta el primero (el cliente), y a ese tambien se le quita el puerto
            "203.0.113.7:51234, 10.0.0.4:443   | 203.0.113.7",
            "[2001:db8::1]:51234, 10.0.0.4     | 2001:db8::1",
            // espacios alrededor, como los deja un proxy al concatenar
            "'  203.0.113.7:51234 , 10.0.0.4'  | 203.0.113.7",
    })
    void laIpSaleSinElPuerto(String cabecera, String esperada) {
        assertThat(LimitePorIpFilter.ipDe(conCabecera(cabecera))).isEqualTo(esperada);
    }

    @Test
    void elMismoClienteDesdeDosConexionesEsLaMismaIp() {
        // lo que de verdad importa: dos conexiones del mismo cliente comparten el contador
        assertThat(LimitePorIpFilter.ipDe(conCabecera("198.51.100.23:40001")))
                .isEqualTo(LimitePorIpFilter.ipDe(conCabecera("198.51.100.23:40002")))
                .isEqualTo(LimitePorIpFilter.ipDe(conCabecera("198.51.100.23")));
    }

    @Test
    void siDespuesDeQuitarElPuertoNoQuedaNadaSeUsaLaIpDelSocket() {
        // un valor como ":51234" no dice de quien es la peticion: se cae a la del socket, igual que sin cabecera
        MockHttpServletRequest peticion = conCabecera(":51234");
        peticion.setRemoteAddr("192.0.2.10");

        assertThat(LimitePorIpFilter.ipDe(peticion)).isEqualTo("192.0.2.10");
    }

    @Test
    void sinCabeceraSeUsaLaIpDelSocket() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr("192.0.2.11");

        assertThat(LimitePorIpFilter.ipDe(peticion)).isEqualTo("192.0.2.11");
    }

    @Test
    void unCorcheteSinCerrarSeDejaTalCualEnVezDeInventarUnaIp() {
        // no es una forma valida; lo importante es no recortarla a algo que coincida con la IP de otro
        assertThat(LimitePorIpFilter.sinPuerto("[2001:db8::1")).isEqualTo("[2001:db8::1");
        assertThat(LimitePorIpFilter.sinPuerto("[]")).isEqualTo("[]");
    }

    private static MockHttpServletRequest conCabecera(String valor) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader(LimitePorIpFilter.CABECERA_IP, valor);
        return peticion;
    }
}
