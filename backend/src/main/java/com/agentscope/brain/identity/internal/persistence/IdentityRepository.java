package com.agentscope.brain.identity.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
    private final JdbcTemplate jdbc;
    public IdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Identity(String userId, String displayName, String status, long securityVersion,
            long credentialVersion, long phoneRevision, String hash, boolean changeRequired,
            String phone, String ownershipSource) { }

    private static final String SELECT = """
            SELECT CAST(u.id AS CHAR) user_id,u.display_name,u.account_status,u.security_version,
                   u.phone_revision,c.credential_version,c.password_hash,c.is_password_change_required,
                   p.phone_normalized,p.ownership_source_ref
            FROM platform_user u LEFT JOIN user_credential c ON c.user_id=u.id
            LEFT JOIN phone_reservation p ON p.owner_user_id=u.id AND p.reservation_status='CURRENT'
            """;

    public List<Identity> byPhone(String phone) {
        return jdbc.query(SELECT + " WHERE p.phone_normalized=?", this::map, phone);
    }

    public List<Identity> byId(String id) {
        return jdbc.query(SELECT + " WHERE u.id=?", this::map, id);
    }

    /** Serialize credential writers on the platform user before locking credential/phone rows. */
    public List<Identity> lockedById(String id) {
        jdbc.queryForList("SELECT id FROM platform_user WHERE id=? FOR UPDATE", String.class, id);
        return jdbc.query(SELECT + " WHERE u.id=? FOR UPDATE", this::map, id);
    }

    public void changeInitialPassword(Identity before, String encoded, String requestId, Instant now) {
        var timestamp = Timestamp.from(now);
        if (jdbc.update("""
                UPDATE user_credential SET password_hash=?,is_password_change_required=0,
                    credential_version=credential_version+1,password_changed_at=?,gmt_modified=?
                WHERE user_id=? AND credential_version=? AND is_password_change_required=1
                """, encoded, timestamp, timestamp, before.userId(), before.credentialVersion()) != 1) {
            throw new IllegalStateException("CREDENTIAL_CONCURRENT_CHANGE");
        }
        if (jdbc.update("""
                UPDATE platform_user SET security_version=security_version+1,modified_by_user_id=?,
                    modification_source_kind='USER_ACTION',modification_bootstrap_batch_id=NULL,gmt_modified=?
                WHERE id=? AND security_version=?
                """, before.userId(), timestamp, before.userId(), before.securityVersion()) != 1) {
            throw new IllegalStateException("SECURITY_CONCURRENT_CHANGE");
        }
        jdbc.update("""
                INSERT INTO identity_audit_event
                    (actor_user_id,target_user_id,action_code,outcome,request_id,occurred_at,gmt_create,gmt_modified)
                VALUES (?,?,'INITIAL_PASSWORD_CHANGE','SUCCESS',?,?,?,?)
                """, before.userId(), before.userId(), requestId, timestamp, timestamp, timestamp);
    }

    public boolean hasPasswordAudit(String userId, String requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM identity_audit_event WHERE actor_user_id=? AND target_user_id=?
                    AND request_id=? AND action_code='INITIAL_PASSWORD_CHANGE' AND outcome='SUCCESS'
                """, Long.class, userId, userId, requestId) == 1;
    }

    private Identity map(ResultSet r, int row) throws SQLException {
        return new Identity(r.getString("user_id"), r.getString("display_name"), r.getString("account_status"),
                r.getLong("security_version"), r.getLong("credential_version"), r.getLong("phone_revision"),
                r.getString("password_hash"), r.getInt("is_password_change_required") == 1,
                r.getString("phone_normalized"), r.getString("ownership_source_ref"));
    }
}
