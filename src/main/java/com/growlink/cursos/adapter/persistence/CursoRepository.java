package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Nivel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// CONSULTAS DE BLOQUE. Las dos colecciones de Curso (habilidades y temario) son LAZY: cualquier consulta cuyo
// resultado se vaya a convertir en DTO tiene que traerlas sin hacer una consulta por curso.
//   - habilidades: LEFT JOIN FETCH en la misma consulta que trae los cursos.
//   - temario: una SEGUNDA consulta (cargarTemario) sobre los mismos ids. Las dos a la vez en un solo JOIN FETCH
//     darian producto cartesiano (|habilidades| x |temario| filas por curso) y, siendo temario una lista con
//     @OrderColumn, es justo lo que hay que evitar.
// Las dos consultas tienen que correr en la MISMA transaccion: la segunda no devuelve nada util, inicializa la
// coleccion de las entidades que ya estan en la sesion (ver CursoService.conTemario).
public interface CursoRepository extends JpaRepository<Curso, Long> {

    // HU-07 "Mis cursos" del publicador (incluye inactivos), con sus habilidades en la misma consulta
    @Query("SELECT c FROM Curso c LEFT JOIN FETCH c.habilidades WHERE c.publicadorUsuarioId = :publicadorUsuarioId")
    List<Curso> findByPublicadorUsuarioId(@Param("publicadorUsuarioId") Long publicadorUsuarioId);

    // CursoCatalogoSeeder: backfill de datos del catalogo, matchea por titulo
    Optional<Curso> findByTitulo(String titulo);

    // HU-13: catalogo general, siempre solo activos, con filtros opcionales
    @Query("SELECT c FROM Curso c LEFT JOIN FETCH c.habilidades WHERE c.activo = true "
            + "AND (:categoria IS NULL OR c.categoria = :categoria) "
            + "AND (:nivel IS NULL OR c.nivel = :nivel)")
    List<Curso> buscarCatalogo(@Param("categoria") Categoria categoria, @Param("nivel") Nivel nivel);

    // los cursos de una ruta guardada (activos o no), con sus habilidades, en una sola consulta
    @Query("SELECT c FROM Curso c LEFT JOIN FETCH c.habilidades WHERE c.id IN :ids")
    List<Curso> buscarPorIdsConHabilidades(@Param("ids") Collection<Long> ids);

    // SEGUNDA consulta del par: inicializa el temario de cursos que ya estan en la sesion. El resultado se
    // ignora a proposito; lo que importa es el efecto sobre la sesion
    @Query("SELECT c FROM Curso c LEFT JOIN FETCH c.temario WHERE c.id IN :ids")
    List<Curso> cargarTemario(@Param("ids") Collection<Long> ids);
}
