/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
import pro.olli.models.Invite; // Keep temporarily for backward compatibility
import pro.olli.models.User;
import pro.olli.models.Verification;

import java.util.Optional;

public interface UserRepository {

    // User management
    Uni<Void> saveUser(User user);
    Uni<Optional<User>> getUser(String email);

    // Email verification management
    Uni<Void> saveVerification(Verification verification);
    Uni<Optional<Verification>> getVerification(String token);
    Uni<Void> deleteVerification(String token);
    Uni<Optional<Verification>> getVerificationByEmail(String email);
    
    // Legacy invite methods (deprecated - for backward compatibility)
    /**
     * @deprecated Use saveVerification instead
     */
    @Deprecated
    default Uni<Void> saveInvite(Invite invite) {
        // Convert Invite to Verification and save
        Verification verification = new Verification(
            invite.getToken(),
            invite.getBusinessId(),
            invite.getEmail(),
            invite.getRole(),
            invite.getCreatedAt(),
            invite.getExpiresAt(),
            invite.isClaimed()
        );
        return saveVerification(verification);
    }
    
    /**
     * @deprecated Use getVerification instead
     */
    @Deprecated
    default Uni<Optional<Invite>> getInvite(String token) {
        return getVerification(token)
            .map(optVerification -> optVerification.map(verification -> 
                new Invite(
                    verification.getToken(),
                    verification.getBusinessId(),
                    verification.getEmail(),
                    verification.getRole(),
                    null, // No invitedBy in Verification
                    verification.getCreatedAt(),
                    verification.getExpiresAt(),
                    verification.isVerified()
                )
            ));
    }
    
    /**
     * @deprecated Use deleteVerification instead
     */
    @Deprecated
    default Uni<Void> deleteInvite(String token) {
        return deleteVerification(token);
    }
    
    /**
     * @deprecated Use getVerificationByEmail instead
     */
    @Deprecated
    default Uni<Optional<Invite>> getInviteByEmail(String email) {
        return getVerificationByEmail(email)
            .map(optVerification -> optVerification.map(verification -> 
                new Invite(
                    verification.getToken(),
                    verification.getBusinessId(),
                    verification.getEmail(),
                    verification.getRole(),
                    null, // No invitedBy in Verification
                    verification.getCreatedAt(),
                    verification.getExpiresAt(),
                    verification.isVerified()
                )
            ));
    }

    // Business management
    Uni<Optional<Business>> getBusiness(String businessId);
    Uni<Void> saveBusiness(Business business);

    // Business user management
    Uni<Void> saveBusinessUser(BusinessUser businessUser);
    Uni<Optional<BusinessUser>> getBusinessUser(String businessId, String userId);
    Uni<Void> updateBusinessUserSyncHistory(String businessId, String userId, BusinessUser updatedUser);
}
