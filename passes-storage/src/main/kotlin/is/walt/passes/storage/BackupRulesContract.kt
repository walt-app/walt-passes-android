package `is`.walt.passes.storage

/**
 * Backup-rules trust contract for `passes-storage`. Encodes what the trust claim
 * "pass data is excluded from cloud backup" means as data the assertion can verify
 * against the consumer's merged manifest.
 *
 * This object is the audit-facing entry point. A security researcher reading the trust
 * claim should land on [REQUIRED_EXCLUDES] to see exactly which files the library
 * insists are excluded from Auto Backup and device-to-device transfer.
 */
public object BackupRulesContract {

    /** Backup rule sections the assertion validates. */
    public enum class Section(public val xmlElement: String) {
        FullBackupContent("full-backup-content"),
        CloudBackup("cloud-backup"),
        DeviceTransfer("device-transfer"),
    }

    /** A single `<exclude domain="..." path="..."/>` entry. */
    public data class RequiredExclude(public val domain: String, public val path: String)

    /** Entries the library requires every consumer rules resource to carry. */
    public val REQUIRED_EXCLUDES: List<RequiredExclude> = listOf(
        RequiredExclude(domain = "database", path = "walt_passes.db"),
        RequiredExclude(domain = "database", path = "walt_passes.db-journal"),
        RequiredExclude(domain = "database", path = "walt_passes.db-wal"),
        RequiredExclude(domain = "database", path = "walt_passes.db-shm"),
        RequiredExclude(domain = "sharedpref", path = "is.walt.passes.storage.key_envelope.xml"),
    )
}
