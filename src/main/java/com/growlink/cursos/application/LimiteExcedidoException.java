package com.growlink.cursos.application;

// la persona ya pidio demasiados roadmaps seguidos (ver LimiteDeGeneraciones): se responde 429 con cuanto esperar
public class LimiteExcedidoException extends RuntimeException {

    private final long segundosParaReintentar;

    public LimiteExcedidoException(long segundosParaReintentar) {
        super("Pediste varios roadmaps seguidos. Espera " + textoDeEspera(segundosParaReintentar)
                + " y vuelve a intentarlo.");
        this.segundosParaReintentar = segundosParaReintentar;
    }

    public long getSegundosParaReintentar() {
        return segundosParaReintentar;
    }

    private static String textoDeEspera(long segundos) {
        if (segundos < 60) {
            return segundos + (segundos == 1 ? " segundo" : " segundos");
        }
        long minutos = (segundos + 59) / 60;
        return minutos + (minutos == 1 ? " minuto" : " minutos");
    }
}
