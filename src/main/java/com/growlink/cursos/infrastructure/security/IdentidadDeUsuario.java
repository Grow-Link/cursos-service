package com.growlink.cursos.infrastructure.security;

// Quien es quien llama, en los terminos que usa ESTE servicio: el usuarioId numerico con el que estan hechas sus
// propias tablas (publicadorUsuarioId, roadmap.usuarioId, curso_completado.usuario_id...) y el rol con el que se
// deciden los permisos.
//
// En modo oidc esto NO sale del token: el `sub` de Microsoft Entra es un texto opaco y el rol es cosa de
// GrowLink, no de Microsoft. Lo resuelve usuarios-service, que es el dueno de esa informacion
// (ver ClienteDeUsuariosService).
public record IdentidadDeUsuario(Long usuarioId, String rol) {
}
