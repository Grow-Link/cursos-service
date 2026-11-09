package com.growlink.cursos.infrastructure.config;

import com.growlink.cursos.infrastructure.security.RegistroDeRechazosOidc;
import com.growlink.cursos.infrastructure.security.TokenAuthenticationFilter;
import com.growlink.cursos.infrastructure.security.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// INTERRUPTOR auth.mode: de que proveedor viene la identidad de quien llama.
//
//   legacy (valor por defecto): como siempre. Un filtro propio que valida el JWT compartido con auth-service y
//          usuarios-service (GROWLINK_JWT_SECRET) y puebla el SecurityContext. Mientras auth.mode no se cambie a
//          mano, NADA de lo que ya funciona cambia.
//   oidc:  el token lo emite y lo firma el proveedor de identidad (Microsoft Entra ID) y este servicio solo lo
//          valida como resource server: firma con las llaves publicas del emisor, emisor, audiencia y vigencia
//          (ver AutenticacionOidcConfig). Este servicio deja de conocer el secreto compartido.
//
// Las dos cadenas protegen exactamente lo mismo (ver reglas()): cambia COMO se comprueba quien llama, no QUE se
// deja entrar. Las dos dejan el mismo Authentication (principal = usuarioId Long, permisos ROLE_<rol>), asi que
// AuthenticatedUser, los controllers y las reglas de roles de los cursos funcionan igual en los dos modos.
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Un auth.mode que no sea ninguno de los dos no se puede dejar pasar en silencio: no se armaria NINGUNA de
    // las dos cadenas y Spring Security pondria la suya por defecto (formulario de login y todo autenticado), con
    // lo que el servicio quedaria inservible y el motivo seria dificil de encontrar. Mejor no arrancar y decirlo.
    public SecurityConfig(@Value("${auth.mode:legacy}") String modo) {
        if (!"legacy".equalsIgnoreCase(modo) && !"oidc".equalsIgnoreCase(modo)) {
            throw new IllegalStateException("auth.mode solo puede ser 'legacy' u 'oidc', y llego: '" + modo + "'");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "auth.mode", havingValue = "legacy", matchIfMissing = true)
    public SecurityFilterChain cadenaLegacy(HttpSecurity http, TokenService tokenService) throws Exception {
        reglas(http).addFilterBefore(new TokenAuthenticationFilter(tokenService),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "auth.mode", havingValue = "oidc")
    public SecurityFilterChain cadenaOidc(HttpSecurity http, JwtDecoder jwtDecoder,
                                          Converter<Jwt, AbstractAuthenticationToken> convertidor,
                                          RegistroDeRechazosOidc registroDeRechazos) throws Exception {
        reglas(http)
                // sin esto, una peticion SIN cabecera Authorization no la atiende el punto de entrada de OAuth2
                // (su filtro no la reconoce como intento de autenticacion) y acabaria en 403 en vez de 401
                .exceptionHandling(e -> e.authenticationEntryPoint(registroDeRechazos))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(registroDeRechazos)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(convertidor)));
        return http.build();
    }

    // Lo que se deja entrar sin token y lo que no: identico en los dos modos, escrito una sola vez para que no se
    // puedan ir separando.
    private static HttpSecurity reglas(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health/**"
                        ).permitAll()
                        // las metricas son datos de operacion (cuantas llamadas a la IA, cuanta cache): solo un ADMIN
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
    }
}
