package server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Db {
    private final HikariDataSource ds;

    public Db(Config cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl());
        hc.setUsername(cfg.dbUser());
        hc.setPassword(cfg.dbPassword());
        hc.setMaximumPoolSize(5);
        hc.setMinimumIdle(0);            // Neon closes idle connections: don't keep any
        hc.setIdleTimeout(60_000);
        hc.setMaxLifetime(240_000);
        hc.setConnectionTimeout(30_000); // Neon may need a few seconds to wake up
        ds = new HikariDataSource(hc);
    }

    public Connection get() throws SQLException { return ds.getConnection(); }

    public void ping() throws SQLException {
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("SELECT 1");
        }
    }
}
