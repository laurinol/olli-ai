/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

@Entity
public class User extends PanacheEntity {
    public String email;
    public String password; // Hashed, e.g., with Bcrypt
}
