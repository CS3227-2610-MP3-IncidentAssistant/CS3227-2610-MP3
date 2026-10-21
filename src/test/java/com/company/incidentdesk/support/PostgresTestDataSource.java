package com.company.incidentdesk.support;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Creates test connections whose schema cannot silently fall back to public. */
public final class PostgresTestDataSource {
    private PostgresTestDataSource() { }

    public static DataSource administrative(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }

    public static DataSource isolated(String url, String username, String password, String schema) {
        DataSource dataSource = new SchemaSelectingDataSource(
                new DriverManagerDataSource(url, username, password), schema);
        try (var connection = dataSource.getConnection()) {
            if (!schema.equals(connection.getSchema())) {
                throw new IllegalStateException("PostgreSQL test connection did not select its isolated schema");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not verify the PostgreSQL test schema", exception);
        }
        return dataSource;
    }

    private static final class SchemaSelectingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final String schema;

        private SchemaSelectingDataSource(DataSource delegate, String schema) {
            this.delegate = delegate;
            this.schema = schema;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return selectSchema(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return selectSchema(delegate.getConnection(username, password));
        }

        private Connection selectSchema(Connection connection) throws SQLException {
            try {
                connection.setSchema(schema);
                return connection;
            } catch (SQLException exception) {
                connection.close();
                throw exception;
            }
        }
    }
}
