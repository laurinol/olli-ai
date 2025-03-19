/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
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

    // Business management
    Uni<Optional<Business>> getBusiness(String businessId);
    Uni<Void> saveBusiness(Business business);

    // Business user management
    Uni<Void> saveBusinessUser(BusinessUser businessUser);
    Uni<Optional<BusinessUser>> getBusinessUser(String businessId, String userId);
    Uni<Void> updateBusinessUserSyncHistory(String businessId, String userId, BusinessUser updatedUser);
}
