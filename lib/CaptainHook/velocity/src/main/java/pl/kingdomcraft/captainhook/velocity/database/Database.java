package pl.kingdomcraft.captainhook.velocity.database;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import pl.kingdomcraft.captainhook.velocity.config.ProxyConfig;

/**
 * The network database: MySQL with an automatic SQLite fallback. This is the only database connection of the
 * whole network, the Bukkit servers reach it through the proxy.
 *
 * There is a single connection and every query runs on one dedicated thread ({@link #submit(SqlTask)}). This keeps
 * transactions isolated and operations ordered (a player's stats, coins and unlocks never race each other),
 * while never blocking a Velocity thread.
 */
public final class Database {

	public static final String SQLITE_FILE = "database.db";

	/**
	 * Re-validating the connection costs a round trip, so only do it after it has been idle for a while.
	 */
	private static final long VALIDATE_AFTER_MILLIS = 30_000;

	private static final int MAX_ATTEMPTS = 3;

	/**
	 * CA that signed the database server certificate (private CA, not in the JVM trust store), bundled in the jar.
	 */
	private static final String CA_RESOURCE = "/db-ca.pem";

	/**
	 * Trust store built from {@link #CA_RESOURCE} on every connect. It only holds the public CA certificate,
	 * so its password protects integrity, not secrecy.
	 */
	private static final String TRUSTSTORE_FILE = "db-truststore.p12";
	private static final String TRUSTSTORE_PASSWORD = "captainhook";

	private final ConnectionFactory factory;
	private final boolean sqlite;
	private final ExecutorService executor;

	/**
	 * Database thread only.
	 */
	private Connection connection;
	private long lastUsed;

	private Database(final ConnectionFactory factory, final Connection connection, final boolean sqlite) {
		this.factory = factory;
		this.connection = connection;
		this.sqlite = sqlite;
		this.lastUsed = System.currentTimeMillis();
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			final Thread thread = new Thread(runnable, "CaptainHook-Database");

			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Connects to MySQL if enabled and reachable, otherwise to SQLite, and creates the tables. Blocking.
	 *
	 * @throws SQLException if not even SQLite could be opened or the tables could not be created
	 */
	public static Database open(final ProxyConfig.Database config, final Path dataDirectory, final Logger logger) throws SQLException {
		Database database = null;

		if (config.mysqlEnabled())
			try {
				final ConnectionFactory factory = mySqlFactory(config, dataDirectory);

				database = new Database(factory, factory.connect(), false);

			} catch (final Exception ex) {
				logger.warn("Could not connect to MySQL ({}: {}), falling back to SQLite!", ex.getClass().getSimpleName(), ex.getMessage());
			}

		if (database == null)
			database = openSqlite(dataDirectory.resolve(SQLITE_FILE));

		final boolean sqlite = database.sqlite;

		try {
			database.submit(connection -> {
				Schema.create(connection, sqlite);

				return null;
			}).join();

		} catch (final RuntimeException ex) {
			database.shutdown();

			throw new SQLException("Could not create the database tables", ex.getCause() != null ? ex.getCause() : ex);
		}

		logger.info("Using the {} database.", sqlite ? "SQLite (" + SQLITE_FILE + ")" : "MySQL");
		return database;
	}

	/**
	 * Opens an SQLite file without creating any tables.
	 */
	public static Database openSqlite(final Path file) throws SQLException {
		final ConnectionFactory factory = sqliteFactory(file);

		return new Database(factory, factory.connect(), true);
	}

	/**
	 * Connects to MySQL with a complete JDBC URL, without creating any tables (the tests use this).
	 */
	public static Database openMySql(final String url, final String user, final String password) throws SQLException {
		final ConnectionFactory factory = mySqlFactory(url, user, password);

		return new Database(factory, factory.connect(), false);
	}

	private static ConnectionFactory mySqlFactory(final ProxyConfig.Database config, final Path dataDirectory) throws IOException, GeneralSecurityException {
		return mySqlFactory("jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.name() + "?" + withTrustStore(config.properties(), dataDirectory),
				config.username(), config.password());
	}

	private static ConnectionFactory mySqlFactory(final String url, final String user, final String password) {
		final Properties credentials = new Properties();

		// Empty values leave the credentials to the URL
		if (!user.isEmpty())
			credentials.setProperty("user", user);

		if (!password.isEmpty())
			credentials.setProperty("password", password);

		// The driver is created directly: DriverManager only sees drivers of the class loader that asks
		return () -> {
			final Connection connection = new com.mysql.cj.jdbc.Driver().connect(url, credentials);

			if (connection == null)
				throw new SQLException("The MySQL driver rejected the connection URL");

			return connection;
		};
	}

	private static ConnectionFactory sqliteFactory(final Path file) {
		return () -> {
			final Connection connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());

			if (connection == null)
				throw new SQLException("The SQLite driver rejected " + file);

			return connection;
		};
	}

	/**
	 * Points Connector/J at a trust store with the bundled database CA, unless the properties already name one.
	 */
	private static String withTrustStore(final String properties, final Path dataDirectory) throws IOException, GeneralSecurityException {
		if (properties.contains("trustCertificateKeyStoreUrl"))
			return properties;

		final Path file = writeTrustStore(dataDirectory);

		return properties + (properties.isEmpty() ? "" : "&")
				+ "trustCertificateKeyStoreUrl=" + file.toUri()
				+ "&trustCertificateKeyStoreType=PKCS12"
				+ "&trustCertificateKeyStorePassword=" + TRUSTSTORE_PASSWORD;
	}

	private static Path writeTrustStore(final Path dataDirectory) throws IOException, GeneralSecurityException {
		final KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(null, null);

		try (InputStream in = Database.class.getResourceAsStream(CA_RESOURCE)) {
			if (in == null)
				throw new IOException(CA_RESOURCE + " is missing from the plugin jar");

			int index = 0;

			for (final Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in))
				store.setCertificateEntry("db-ca-" + index++, certificate);

			if (index == 0)
				throw new IOException(CA_RESOURCE + " contains no certificate");
		}

		Files.createDirectories(dataDirectory);

		final Path file = dataDirectory.resolve(TRUSTSTORE_FILE);

		try (OutputStream out = Files.newOutputStream(file)) {
			store.store(out, TRUSTSTORE_PASSWORD.toCharArray());
		}

		return file;
	}

	/**
	 * Opens another SQLite file (e.g. the old fallback database), runs the task on it in a transaction and closes it.
	 */
	public static <T> T withSqlite(final Path file, final SqlTask<T> task) throws SQLException {
		try (Connection connection = sqliteFactory(file).connect()) {
			connection.setAutoCommit(false);

			final T result = task.run(connection);

			connection.commit();
			return result;
		}
	}

	/**
	 * Waits for queued queries to finish and closes the connection.
	 *
	 * @return false if the queries did not finish in time, some changes may be lost
	 */
	public boolean shutdown() {
		boolean finished = true;

		this.executor.shutdown();

		try {
			finished = this.executor.awaitTermination(10, TimeUnit.SECONDS);

		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}

		try {
			if (this.connection != null)
				this.connection.close();

		} catch (final SQLException ignored) {
		}

		return finished;
	}

	public boolean isMySql() {
		return !this.sqlite;
	}

	public boolean isSqlite() {
		return this.sqlite;
	}

	/**
	 * Runs the task in a transaction on the database thread. Fails the future with SQLException on database errors.
	 * Transactions killed by a MySQL deadlock (the website writes to the same tables) are retried.
	 */
	public <T> CompletableFuture<T> submit(final SqlTask<T> task) {
		final CompletableFuture<T> future = new CompletableFuture<>();

		try {
			this.executor.execute(() -> {
				for (int attempt = 1;; attempt++)
					try {
						future.complete(this.execute(task));
						return;

					} catch (final SQLException ex) {
						if (attempt < MAX_ATTEMPTS && "40001".equals(ex.getSQLState()))
							continue;

						future.completeExceptionally(ex);
						return;

					} catch (final Throwable t) {
						future.completeExceptionally(t);
						return;
					}
			});

		} catch (final RejectedExecutionException ex) {
			future.completeExceptionally(new SQLException("The database is shut down"));
		}

		return future;
	}

	/**
	 * Runs the task within a transaction on the calling thread.
	 */
	private <T> T execute(final SqlTask<T> task) throws SQLException {
		final Connection connection = this.connection();

		try {
			connection.setAutoCommit(false);

			final T result = task.run(connection);

			connection.commit();
			this.lastUsed = System.currentTimeMillis();

			return result;

		} catch (final SQLException | RuntimeException ex) {
			try {
				connection.rollback();
			} catch (final SQLException ignored) {
			}

			this.lastUsed = 0; // re-validate the connection before the next query

			throw ex;
		}
	}

	private Connection connection() throws SQLException {
		if (System.currentTimeMillis() - this.lastUsed > VALIDATE_AFTER_MILLIS && !this.isConnected()) {
			try {
				if (this.connection != null)
					this.connection.close();

			} catch (final SQLException ignored) {
			}

			this.connection = null;
			this.connection = this.factory.connect();
		}

		if (this.connection == null)
			throw new SQLException("Database is not connected");

		return this.connection;
	}

	private boolean isConnected() {
		try {
			return this.connection != null && this.connection.isValid(2);

		} catch (final SQLException ex) {
			return false;
		}
	}

	@FunctionalInterface
	private interface ConnectionFactory {
		Connection connect() throws SQLException;
	}
}
