package com.techjobs.finder.web;

import com.techjobs.finder.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * De quem é esta requisição, para efeito de limite de uso.
 *
 * <p>Ordem que importa: <strong>usuário autenticado primeiro, IP só como último recurso</strong>.
 * O IP não identifica ninguém — atrás de NAT corporativo ou de operadora móvel, milhares de
 * pessoas compartilham um, e quem quer burlar troca de endereço em segundos. Ele serve para
 * conter abuso anônimo, não para dizer quem é quem. Agora que existe sessão, o limite de
 * quem está autenticado acompanha a conta, não a rede de onde ela se conecta.
 */
public interface ClientKeyResolver {

    String resolve(HttpServletRequest request);

    /** Conta autenticada quando houver; IP quando não houver. */
    class SessionOrAddress implements ClientKeyResolver {

        @Override
        public String resolve(HttpServletRequest request) {
            AuthenticatedUser current = authenticated();
            if (current != null) {
                return "user:" + current.id();
            }
            return "addr:" + clientAddress(request);
        }

        /**
         * A identidade vem do contexto de segurança, preenchido pelo filtro de sessão
         * depois de validar o cookie contra o banco. Nada que o cliente escreva chega aqui.
         */
        private AuthenticatedUser authenticated() {
            Authentication authentication =
                    SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return null;
            }
            return authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
        }

        /**
         * Só considera {@code X-Forwarded-For} porque o nginx do projeto o preenche. Em um
         * ambiente sem proxy confiável na frente, o cabeçalho é falsificável e o limite por
         * IP passa a valer pouco — motivo a mais para a chave preferida ser a conta.
         */
        private String clientAddress(HttpServletRequest request) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            }
            return request.getRemoteAddr();
        }
    }
}
