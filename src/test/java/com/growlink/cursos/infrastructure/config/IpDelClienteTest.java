package com.growlink.cursos.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

// De que IP es una peticion, para el tope por IP (ver LimitePorIpFilter.ipDe). La IP sale de getRemoteAddr(), donde
// el RemoteIpValve de Tomcat ya dejo la que agrego el proxy de confianza (server.forward-headers-strategy=native).
// Lo delicado es el puerto: Azure App Service manda el cliente como IP:puerto y el valve copia ese valor tal cual,
// asi que si el puerto quedara pegado a la IP, cada conexion contaria como una IP nueva y el tope no frenaria a
// nadie (eso lo prueba LimitePorIpTest con el servicio levantado); aqui se prueban una por una las formas en que
// puede quedar el valor.
class IpDelClienteTest {

    @ParameterizedTest(name = "getRemoteAddr() \"{0}\" es la IP {1}")
    @CsvSource(delimiter = '|', value = {
            // IPv4, con y sin el puerto que agrega el proxy
            "203.0.113.7                       | 203.0.113.7",
            "203.0.113.7:51234                 | 203.0.113.7",
            // IPv6 con puerto: va entre corchetes, y lo que cuenta es lo de adentro
            "[2001:db8::1]:51234               | 2001:db8::1",
            "[2001:db8::1]                     | 2001:db8::1",
            // IPv6 sin corchetes: sus ':' son de la direccion, no hay puerto que quitar
            "2001:db8::1                       | 2001:db8::1",
            "::1                               | ::1",
            // espacios alrededor
            "'  203.0.113.7:51234  '           | 203.0.113.7",
    })
    void laIpSaleSinElPuerto(String remoteAddr, String esperada) {
        assertThat(LimitePorIpFilter.ipDe(desde(remoteAddr))).isEqualTo(esperada);
    }

    @Test
    void elMismoClienteDesdeDosConexionesEsLaMismaIp() {
        // lo que de verdad importa: dos conexiones del mismo cliente comparten el contador
        assertThat(LimitePorIpFilter.ipDe(desde("198.51.100.23:40001")))
                .isEqualTo(LimitePorIpFilter.ipDe(desde("198.51.100.23:40002")))
                .isEqualTo(LimitePorIpFilter.ipDe(desde("198.51.100.23")));
    }

    @Test
    void elXForwardedForQueMandeElClienteNoCambiaLaClave() {
        // la razon de contar la del socket: esta cabecera la escribe quien llama. Cuando se contaba su primer
        // valor, bastaba mandar uno distinto en cada peticion para tener un contador nuevo cada vez y no toparse
        // nunca con el limite. Detras de un proxy de confianza, el RemoteIpValve ya puso en getRemoteAddr() el
        // valor que agrego el proxy, y lo que el cliente escriba no se mira
        MockHttpServletRequest peticion = desde("203.0.113.7:51234");
        peticion.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(LimitePorIpFilter.ipDe(peticion)).isEqualTo("203.0.113.7");
    }

    @Test
    void siDespuesDeQuitarElPuertoNoQuedaNadaNoSeInventaUnaIp() {
        // un valor como ":51234" no dice de quien es la peticion: cae en una clave aparte, no en una vacia que
        // juntaria en un solo contador a clientes que no tienen nada que ver
        assertThat(LimitePorIpFilter.ipDe(desde(":51234"))).isEqualTo("desconocida");
    }

    @Test
    void sinIpDeSocketTampocoSeInventaUnaIp() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr(null);

        assertThat(LimitePorIpFilter.ipDe(peticion)).isEqualTo("desconocida");
    }

    @Test
    void unCorcheteSinCerrarSeDejaTalCualEnVezDeInventarUnaIp() {
        // no es una forma valida; lo importante es no recortarla a algo que coincida con la IP de otro
        assertThat(LimitePorIpFilter.sinPuerto("[2001:db8::1")).isEqualTo("[2001:db8::1");
        assertThat(LimitePorIpFilter.sinPuerto("[]")).isEqualTo("[]");
    }

    @Test
    void laIpv6SinCorchetesYConPuertoSeQuedaConElPuerto() {
        // CASO QUE NO SE PUEDE RESOLVER, escrito aqui para que quede claro cual es el limite: "2001:db8::1:51234"
        // es indistinguible de una IPv6 a secas (todos sus ':' podrian ser de la direccion), asi que se devuelve
        // entera. Si algun dia un proxy mandara la IPv6 asi, sin corchetes y con puerto, cada conexion de ese
        // cliente seria otra clave y el tope no lo frenaria. El estandar y Azure usan corchetes, que es el caso
        // de arriba y si queda bien contado.
        assertThat(LimitePorIpFilter.ipDe(desde("2001:db8::1:51234"))).isEqualTo("2001:db8::1:51234");
        assertThat(LimitePorIpFilter.ipDe(desde("2001:db8::1:51235"))).isEqualTo("2001:db8::1:51235");
    }

    private static MockHttpServletRequest desde(String remoteAddr) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr(remoteAddr);
        return peticion;
    }
}
