/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

public class User {
    public String email;
    public String password;

    public User() {} // For JSON deserialization
    public User(String email, String password) {
        this.email = email;
        this.password = password;
    }
}
