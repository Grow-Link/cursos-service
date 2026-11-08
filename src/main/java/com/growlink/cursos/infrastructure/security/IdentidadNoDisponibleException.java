package com.growlink.cursos.infrastructure.security;

import org.springframework.security.core.AuthenticationException;

// No se pudo saber quien es quien llama porque usuarios-service no contesto (cayo, se demoro, o devolvio un 5xx).
// OJO: esto NO quiere decir que el token sea malo. Por eso termina en 503 y no en 401: el token puede estar
// perfecto y el problema ser nuestro, asi que al cliente se le dice "vuelve a intentar", no "tu credencial no
// sirve" (que lo mandaria a iniciar sesion otra vez sin motivo). Ver RegistroDeRechazosOidc.
//
// Lo que NUNCA hace es dejar pasar la peticion: sin identidad no se sabe de quien seria el curso ni el roadmap,
// ni que permisos tiene, asi que la unica salida segura es rechazarla. Tampoco se cae a leer los claims del
// token: eso seria aceptar como rol lo que diga el propio token, que es justo lo que este modo evita.
public class IdentidadNoDisponibleException extends AuthenticationException {

    public IdentidadNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public IdentidadNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
