package com.techjobs.finder.security;

/**
 * Identidade de quem fez a requisição, derivada exclusivamente da sessão validada.
 *
 * <p>Record e não entidade: o que circula pelas camadas é o mínimo necessário para decidir
 * autorização. Nada aqui vem do corpo, da query string ou de um cabeçalho escolhido pelo
 * cliente — um {@code userId} enviado pelo frontend jamais chega a virar um destes.
 *
 * @param id        chave do usuário no banco
 * @param sessionId sessão que autenticou esta requisição, para revogar exatamente ela
 * @param anonymous conta sem e-mail e senha, criada só para guardar o currículo
 */
public record AuthenticatedUser(Long id, Long sessionId, boolean anonymous) {

    public boolean owns(Long ownerId) {
        return ownerId != null && ownerId.equals(id);
    }
}
