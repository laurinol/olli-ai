/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
import pro.olli.models.User;
import pro.olli.models.Verification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    // User management
    Uni<Void> saveUser(User user);
    Uni<Optional<User>> getUser(String userId);
    Uni<Optional<User>> getUserByEmail(String email);

    // Email verification management
    Uni<Void> saveVerification(Verification verification);
    Uni<Optional<Verification>> getVerification(String token);
    Uni<Void> deleteVerification(String token);
    Uni<Optional<Verification>> getVerificationByEmail(String email);

    // Business management
    Uni<Optional<Business>> getBusiness(String businessId);
    Uni<Void> saveBusiness(Business business);
    Uni<List<Business>> getBusinessesDueForBilling(Instant dueDate);
    Uni<Optional<Business>> getBusinessBySubscriptionId(String subscriptionId);

    // Business user management
    Uni<Void> saveBusinessUser(BusinessUser businessUser);
    Uni<Optional<BusinessUser>> getBusinessUser(String businessId, String userId);
    Uni<Void> updateBusinessUserSyncHistory(String businessId, String userId, BusinessUser updatedUser);
    
    // User permissions
    /**
     * Check if a user is in a business (any role).
     */
    Uni<Boolean> isUserInBusiness(String userId, String businessId);
    
    /**
     * Check if a user is an admin for a business.
     */
    Uni<Boolean> isUserAdmin(String userId, String businessId);
}
