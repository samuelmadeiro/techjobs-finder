package com.techjobs.finder.repository;

import com.techjobs.finder.entity.ResumeContent;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Acesso ao arquivo e ao texto integral do currículo.
 *
 * <p>Repositório separado de propósito: quem lê o perfil não tem como esbarrar no
 * binário sem passar por aqui.
 */
public interface ResumeContentRepository extends JpaRepository<ResumeContent, Long> {

    /**
     * Registros ainda em claro, para a recifragem em segundo plano. Apoiado pelo índice
     * parcial criado na V7, que deixa de custar quando não houver mais pendências.
     */
    @Query("SELECT c FROM ResumeContent c WHERE c.encryptionKeyId IS NULL")
    List<ResumeContent> findPendingEncryption(Limit limit);
}
