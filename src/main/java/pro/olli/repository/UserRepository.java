/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;

import java.util.Map;
import java.util.Optional;

public interface UserRepository {

    // From AuthController
    Uni<Void> saveUser(String email, Map<String, Object> userData);
    Uni<Void> saveInvite(String token, Map<String, Object> inviteData);
    Uni<Optional<Map<String, Object>>> getInvite(String token);
    Uni<Void> deleteInvite(String token);

    // For AuthResource
    Uni<Optional<Map<String, Object>>> getBusiness(String businessId);
    Uni<Void> saveBusiness(String businessId, Map<String, Object> businessData);
    Uni<Void> saveBusinessUser(String businessId, String userId, Map<String, Object> userData);
}
