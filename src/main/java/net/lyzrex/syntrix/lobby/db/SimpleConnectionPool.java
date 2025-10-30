package net.lyzrex.syntrix.lobby.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Very small connection pool tailored for the lobby plugin.
 * It keeps a bounded amount of connections alive and returns
 * lightweight proxies that release the underlying connection
 * back into the pool when {@link Connection#close()} is called.
 */
public final class SimpleConnectionPool implements AutoCloseable {

    private final String jdbcUrl;
    private final Properties baseProperties;
    private final int maxSize;
    private final long borrowTimeoutMillis;
    private final Logger logger;

    private final BlockingQueue<PooledConnection> available = new LinkedBlockingQueue<>();
    private final Set<PooledConnection> all = ConcurrentHashMap.newKeySet();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SimpleConnectionPool(String jdbcUrl,
                                Properties baseProperties,
                                int maxSize,
                                long borrowTimeoutMillis,
                                Logger logger) {
        this.jdbcUrl = jdbcUrl;
        this.baseProperties = baseProperties;
        this.maxSize = Math.max(1, maxSize);
        this.borrowTimeoutMillis = Math.max(100L, borrowTimeoutMillis);
        this.logger = logger;
    }

    public Connection borrow() throws SQLException {
        if (closed.get()) {
            throw new SQLException("Connection pool has been closed");
        }
        while (true) {
            PooledConnection pooled = available.poll();
            if (pooled == null) {
                if (total.get() < maxSize) {
                    pooled = createNewConnection();
                } else {
                    try {
                        pooled = available.poll(borrowTimeoutMillis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted while waiting for a database connection", e);
                    }
                    if (pooled == null) {
                        throw new SQLException("Timed out waiting for a database connection from the pool");
                    }
                }
            }
            if (pooled == null) {
                continue;
            }
            if (!pooled.acquire()) {
                discard(pooled);
                continue;
            }
            return pooled.proxy();
        }
    }

    private PooledConnection createNewConnection() throws SQLException {
        while (true) {
            int current = total.get();
            if (current >= maxSize) {
                return null;
            }
            if (total.compareAndSet(current, current + 1)) {
                break;
            }
        }
        try {
            Properties props = cloneProperties(baseProperties);
            Connection delegate = DriverManager.getConnection(jdbcUrl, props);
            PooledConnection pooled = new PooledConnection(delegate);
            all.add(pooled);
            return pooled;
        } catch (SQLException ex) {
            total.decrementAndGet();
            throw ex;
        }
    }

    private Properties cloneProperties(Properties source) {
        Properties copy = new Properties();
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    void returnToPool(PooledConnection pooled) {
        if (closed.get()) {
            pooled.closeSilently();
            return;
        }
        available.offer(pooled);
    }

    void discard(PooledConnection pooled) {
        pooled.invalidate();
        available.remove(pooled);
        if (!all.remove(pooled)) {
            return;
        }
        pooled.closeSilently();
        total.decrementAndGet();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int totalConnections() {
        return total.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        available.clear();
        for (PooledConnection pooled : all) {
            pooled.closeSilently();
        }
        all.clear();
    }

    final class PooledConnection implements InvocationHandler {
        private final Connection delegate;
        private final Connection proxy;
        private final AtomicBoolean inUse = new AtomicBoolean();
        private final AtomicBoolean valid = new AtomicBoolean(true);

        PooledConnection(Connection delegate) {
            this.delegate = delegate;
            this.proxy = (Connection) Proxy.newProxyInstance(
                    delegate.getClass().getClassLoader(),
                    new Class[] { Connection.class },
                    this
            );
        }

        Connection proxy() {
            return proxy;
        }

        boolean acquire() {
            if (!valid.get() || !inUse.compareAndSet(false, true)) {
                return false;
            }
            try {
                if (delegate.isClosed()) {
                    inUse.set(false);
                    return false;
                }
                if (!delegate.isValid(2)) {
                    inUse.set(false);
                    return false;
                }
            } catch (SQLException ex) {
                logger.log(Level.WARNING, "[DB] Connection validation failed", ex);
                inUse.set(false);
                return false;
            }
            return true;
        }

        void release() {
            if (!inUse.compareAndSet(true, false)) {
                return;
            }
            if (!valid.get()) {
                discard(this);
                return;
            }
            returnToPool(this);
        }

        void closeSilently() {
            try {
                delegate.close();
            } catch (SQLException ex) {
                logger.log(Level.FINE, "[DB] Failed to close pooled connection", ex);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                release();
                return null;
            }
            if ("isClosed".equals(name)) {
                if (!inUse.get()) {
                    return true;
                }
            }
            if (!inUse.get()) {
                throw new SQLException("Connection already returned to pool");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getTargetException();
                if (cause instanceof SQLException sql && shouldDiscard(sql)) {
                    valid.set(false);
                    inUse.set(false);
                    discard(this);
                }
                throw cause;
            }
        }

        private boolean shouldDiscard(SQLException ex) {
            return ex.getSQLState() != null && ex.getSQLState().startsWith("08");
        }

        void invalidate() {
            valid.set(false);
            inUse.set(false);
        }
    }
}