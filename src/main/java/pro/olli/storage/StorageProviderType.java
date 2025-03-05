package pro.olli.storage;

/**
 * Enum representing supported storage providers.
 */
public enum StorageProviderType {
    GOOGLE("google"),
    DROPBOX("dropbox"),
    //ONEDRIVE("onedrive"),
    S3("s3"),
    SFTP("sftp");

    private final String value;

    StorageProviderType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static StorageProviderType fromString(String value) {
        for (StorageProviderType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null; // Or throw exception
    }
}
