/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.connector;

/**
 * Enum representing supported connector providers.
 */
public enum ConnectorType {
    LOCAL("local"),
    NETWORK("network"),  // Samba
    GOOGLE("google"),
    DROPBOX("dropbox"),
    //ONEDRIVE("onedrive"),
    S3("s3"),
    SFTP("sftp");

    private final String value;

    ConnectorType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ConnectorType fromString(String value) {
        for (ConnectorType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null; // Or throw exception
    }
}
