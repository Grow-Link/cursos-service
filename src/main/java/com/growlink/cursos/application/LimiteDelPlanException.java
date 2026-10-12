package com.growlink.cursos.application;

// Lo que se pide pasa del limite del plan gratuito (hoy: un solo tema = una sola area por roadmap). Es un caso de
// "meta no viable" para el plan actual, asi que sale como 422 con un mensaje que explica que hacer.
public class LimiteDelPlanException extends MetaNoViableException {
    public LimiteDelPlanException(String mensaje) {
        super(mensaje);
    }
}
