package com.example.library.identity.application.port.in;

public interface BootstrapDevMemberUseCase {

    /** Crée le membre de test s'il n'existe pas (poste de développement uniquement, RM-LOAN-30). */
    DevMemberBootstrapResult bootstrap(BootstrapDevMemberCommand command);
}
