package pl.kingdomcraft.captainhook.velocity.database;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlTask<T> {

	T run(Connection connection) throws SQLException;
}
