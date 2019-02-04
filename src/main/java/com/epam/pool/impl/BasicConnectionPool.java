package com.epam.pool.impl;

import com.epam.pool.ConnectionPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class BasicConnectionPool implements ConnectionPool {
    private static final Logger LOGGER = LogManager.getLogger(BasicConnectionPool.class);
    private final String driverClass;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final int poolCapacity;
    private final Queue<Connection> pool;
    private final Semaphore semaphore;
    private final Lock lock = new ReentrantLock();
    private int createdConnectionCount = 0;

    public BasicConnectionPool(String driverClass, String jdbcUrl, String user, String
            password, int poolCapacity) {
        this.driverClass = driverClass;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.poolCapacity = poolCapacity;
        this.pool = new ArrayDeque<>(poolCapacity);
        this.semaphore = new Semaphore(poolCapacity);
        initDriver(this.driverClass);
    }

    public Connection getConnection() {
        try {
            semaphore.acquire();
            lock.lock();
            if (createdConnectionCount < poolCapacity) {
                createdConnectionCount++;
                InvocationHandler handler = this.getHandler();
                Class[] classes = {Connection.class};
                return (Connection) Proxy.newProxyInstance(null, classes, handler);
            }
            return pool.poll();
        } catch (Exception e) {
            LOGGER.error(e);
            throw new IllegalStateException();
        } finally {
            lock.unlock();
        }
    }


    private void initDriver(String driverClass) {
        try {
            lock.lock();
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            LOGGER.error(e);
            throw new IllegalStateException("Driver cannot be found", e);
        } finally {
            lock.unlock();
        }
    }

     public void releaseConnection(Connection connection) {
        try {
            LOGGER.info("releaseConnection()");
            lock.lock();
            pool.add(connection);
        } finally {
            semaphore.release();
            lock.unlock();

        }
    }

    private Class<?>[] initClassArray(Object[] args) {
        Class<?>[] classes = new Class[args.length];
        for (int i = 0; i < args.length; ++i) {
            classes[i] = args[i].getClass();
        }
        return classes;
    }

  private InvocationHandler getHandler() throws SQLException {
      final BasicConnectionPool basicConnectionPool = this;
      return  new InvocationHandler() {
          Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
              String methodName = method.getName();
              final BasicConnectionPool connectionPool = basicConnectionPool;
              if (methodName.equals("close")) {
                  connectionPool.releaseConnection((Connection) proxy);
                  return null;
              } else {
                  Class<?>[] classes = args != null ? initClassArray(args) : new Class[]{};
                  Class connectionClass = connection.getClass();
                  Method connectionMethod = connectionClass.getMethod(methodName, classes);
                  return connectionMethod.invoke(connection, args);
              }
          }
      };

  }
}
