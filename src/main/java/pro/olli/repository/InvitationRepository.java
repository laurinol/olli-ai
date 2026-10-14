/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;
import pro.olli.models.Invitation;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing team invitations.
 */
public interface InvitationRepository {

    /**
     * Saves an invitation to the database.
     *
     * @param invitation The invitation to save
     * @return A Uni that completes when the operation is done
     */
    Uni<Void> saveInvitation(Invitation invitation);

    /**
     * Gets an invitation by its unique ID.
     *
     * @param id The invitation ID
     * @return A Uni containing the invitation if found, or empty if not found
     */
    Uni<Optional<Invitation>> getInvitation(String id);

    /**
     * Gets all invitations for a specific business.
     *
     * @param businessId The business ID
     * @return A Uni containing a list of invitations
     */
    Uni<List<Invitation>> getInvitationsByBusiness(String businessId);

    /**
     * Gets all invitations sent to a specific email address.
     *
     * @param email The email address
     * @return A Uni containing a list of invitations
     */
    Uni<List<Invitation>> getInvitationsByEmail(String email);

    /**
     * Gets all invitations sent by a specific user.
     *
     * @param userId The user ID who sent the invitations
     * @return A Uni containing a list of invitations
     */
    Uni<List<Invitation>> getInvitationsBySender(String userId);

    /**
     * Gets all pending (not accepted and not expired) invitations for a business.
     *
     * @param businessId The business ID
     * @return A Uni containing a list of pending invitations
     */
    Uni<List<Invitation>> getPendingInvitations(String businessId);

    /**
     * Deletes an invitation by its ID.
     *
     * @param id The invitation ID to delete
     * @return A Uni that completes when the operation is done
     */
    Uni<Void> deleteInvitation(String id);

    /**
     * Checks if a user is an admin for a specific business.
     * This is used to verify if a user has permission to send invitations.
     *
     * @param userId The user ID to check
     * @param businessId The business ID
     * @return A Uni containing true if the user is an admin, false otherwise
     */
    Uni<Boolean> isUserAdmin(String userId, String businessId);
}
