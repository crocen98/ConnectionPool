package com.epam.pool;

import java.sql.Connection;

public interface ConnectionPool {
    Connection getConnection();

}
