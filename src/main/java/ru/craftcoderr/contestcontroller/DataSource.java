package ru.craftcoderr.contestcontroller;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.Configuration;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class DataSource {

    private static Properties props = new Properties();
    private static HikariDataSource ds;

    DataSource(Configuration configData) {

        Properties props = new Properties();

        props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
        props.setProperty("dataSource.user", configData.getString("db.user"));
        props.setProperty("dataSource.password", configData.getString("db.password"));
        props.setProperty("dataSource.databaseName", configData.getString("db.name"));
        props.put("dataSource.portNumber", configData.getInt("db.port"));
        props.put("dataSource.serverName", configData.getString("db.host"));
        props.put("dataSource.logWriter", new PrintWriter(System.out));

        HikariConfig config = new HikariConfig(props);
        ds = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

}