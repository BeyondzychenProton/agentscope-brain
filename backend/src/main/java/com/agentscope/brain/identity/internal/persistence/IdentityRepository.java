package com.agentscope.brain.identity.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    private Identity map(ResultSet r, int row) throws SQLException {
        return new Identity(r.getString("user_id"), r.getString("display_name"), r.getString("account_status"),
                r.getLong("security_version"), r.getLong("credential_version"), r.getLong("phone_revision"),
                r.getString("password_hash"), r.getInt("is_password_change_required") == 1,
                r.getString("phone_normalized"), r.getString("ownership_source_ref"));
    }
}
