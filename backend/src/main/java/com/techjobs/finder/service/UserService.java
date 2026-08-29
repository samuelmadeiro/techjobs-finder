package com.techjobs.finder.service;

import com.techjobs.finder.dto.auth.AuthDtos.SessionResponse;
import com.techjobs.finder.entity.AppUser;
import com.techjobs.finder.exception.ResourceNotFoundException;
import com.techjobs.finder.repository.AppUserRepository;
import com.techjobs.finder.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leitura da conta do próprio usuário autenticado. */
@Service
public class UserService {

    private final AppUserRepository userRepository;

    public UserService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Descreve a conta da sessão. Só devolve o que o dono já sabe sobre si: nada de hash
     * de senha, token ou data de última sessão.
     */
    @Transactional(readOnly = true)
    public SessionResponse describe(AuthenticatedUser current) {
        AppUser user = userRepository.findById(current.id())
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", current.id()));
        return new SessionResponse(user.getId(), user.isAnonymous(), user.getEmail());
    }
}
