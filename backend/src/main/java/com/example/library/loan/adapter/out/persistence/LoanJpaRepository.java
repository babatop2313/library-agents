package com.example.library.loan.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LoanJpaRepository extends JpaRepository<LoanJpaEntity, UUID> {

    /** Emprunts actifs d'un membre ; l'index partiel uk_loan_member_book_active sert aussi cette lecture. */
    List<LoanJpaEntity> findByMemberIdAndReturnedOnIsNullOrderByDueOnAscIdAsc(UUID memberId);

    /**
     * Verrou de ligne (FOR UPDATE) : deux retours simultanés du même emprunt sont sérialisés.
     * Le délai d'attente vient de setLocalLockTimeout (set_config), seule source du délai.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LoanJpaEntity> findByIdAndMemberId(UUID id, UUID memberId);

    /** Retour conditionnel : ne modifie aucune ligne si l'emprunt est déjà rendu ou n'appartient pas au membre. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE loan SET returned_on = :returnedOn"
            + " WHERE id = :id AND member_id = :memberId AND returned_on IS NULL", nativeQuery = true)
    int markReturned(@Param("id") UUID id, @Param("memberId") UUID memberId,
                     @Param("returnedOn") LocalDate returnedOn);

    /**
     * Délai maximal d'attente d'un verrou pour la transaction en cours (équivalent de {@code SET LOCAL}) :
     * aucun thread n'attend indéfiniment le verrou d'un membre ou la ligne d'un livre.
     */
    @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);

    /**
     * Verrou consultatif de transaction : libéré automatiquement au commit ou au rollback. La fonction renvoie
     * {@code void}, ce que Spring Data ne sait pas mapper : d'où l'enveloppe {@code count(*)}.
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:namespace, hashtext(CAST(:member AS text)))) l",
            nativeQuery = true)
    long advisoryLockMember(@Param("namespace") int namespace, @Param("member") String member);
}
