package pl.kingdomcraft.captainhook.velocity;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.database.Schema;

/**
 * The database of a test: SQLite in a temporary folder, or MySQL when CAPTAINHOOK_TEST_MYSQL is set, e.g.
 * jdbc:mysql://127.0.0.1:3306/test?user=root&amp;allowPublicKeyRetrieval=true&amp;useSSL=false (all ch_ tables of
 * that database are dropped first).
 */
final class TestDatabases {

	static final String MYSQL_ENV = "CAPTAINHOOK_TEST_MYSQL";

	private TestDatabases() {
	}

	static boolean isMySql() {
		final String url = System.getenv(MYSQL_ENV);

		return url != null && !url.isBlank();
	}

	static Database open(final Path directory) throws Exception {
		final boolean mysql = isMySql();
		final Database database = mysql ? Database.openMySql(System.getenv(MYSQL_ENV), "", "") : Database.openSqlite(directory.resolve("test.db"));

		database.submit(connection -> {
			if (mysql)
				try (Statement statement = connection.createStatement()) {
					final List<String> tables = new ArrayList<>();

					try (ResultSet result = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'ch\\\\_%'")) {
						while (result.next())
							tables.add(result.getString(1));
					}

					for (final String table : tables)
						statement.executeUpdate("DROP TABLE " + table);
				}

			Schema.create(connection, !mysql);
			Schema.create(connection, !mysql); // creating twice must be harmless, it runs on every start

			return null;
		}).get(30, TimeUnit.SECONDS);

		return database;
	}
}
