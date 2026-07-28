package com.recsys.infrastructure.redis;

import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A pipelined batch borrows a dedicated connection and disables auto-flush on it. If that
 * lifecycle is interrupted, the connection is no longer in a state the pool can hand out:
 *
 * <ul>
 *   <li>A callback that throws after queueing but before {@code flushCommands()} leaves those
 *       commands buffered. {@code setAutoFlushCommands(true)} does <em>not</em> flush them, so
 *       returning the connection lets the next borrower's flush execute a failed batch's
 *       writes.</li>
 *   <li>A {@code setAutoFlushCommands(false)} that itself throws never reaches the restore,
 *       so the connection would go back with auto-flush off and wedge the next borrower.</li>
 * </ul>
 *
 * <p>Both must destroy the connection instead — the same discipline
 * {@code executePrimaryRead} already applies.
 */
class LettuceRedisExecutorPipelineTest {

    @SuppressWarnings("unchecked")
    private GenericObjectPool<StatefulRedisConnection<String, String>> pool() {
        return mock(GenericObjectPool.class);
    }

    @SuppressWarnings("unchecked")
    private StatefulRedisConnection<String, String> connection() {
        return mock(StatefulRedisConnection.class);
    }

    @Test
    void callbackThrowing_invalidatesTheConnectionInsteadOfReturningIt() throws Exception {
        var pool = pool();
        var conn = connection();
        when(pool.borrowObject()).thenReturn(conn);

        assertThatThrownBy(() -> new LettuceRedisExecutor(null, pool, false, () -> 0L)
                .executePipelined(c -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(pool).invalidateObject(conn);
        verify(pool, never()).returnObject(conn);
    }

    @Test
    void disablingAutoFlushThrowing_invalidatesTheConnection() throws Exception {
        var pool = pool();
        var conn = connection();
        when(pool.borrowObject()).thenReturn(conn);
        doThrow(new IllegalStateException("connection closed"))
                .when(conn).setAutoFlushCommands(false);

        assertThatThrownBy(() -> new LettuceRedisExecutor(null, pool, false, () -> 0L)
                .executePipelined(c -> { }))
                .isInstanceOf(IllegalStateException.class);

        verify(pool).invalidateObject(conn);
        verify(pool, never()).returnObject(conn);
    }

    @Test
    void successfulBatch_returnsTheConnectionAndRestoresAutoFlush() throws Exception {
        var pool = pool();
        var conn = connection();
        when(pool.borrowObject()).thenReturn(conn);

        new LettuceRedisExecutor(null, pool, false, () -> 0L).executePipelined(c -> { });

        verify(conn).setAutoFlushCommands(false);
        verify(conn).setAutoFlushCommands(true);
        verify(pool).returnObject(conn);
        verify(pool, never()).invalidateObject(conn);
    }

    @Test
    void returnObjectThrowing_fallsBackToInvalidate() throws Exception {
        var pool = pool();
        var conn = connection();
        when(pool.borrowObject()).thenReturn(conn);
        doThrow(new IllegalStateException("pool closed")).when(pool).returnObject(conn);

        // The pool failing to take the connection back must not surface as a batch failure.
        new LettuceRedisExecutor(null, pool, false, () -> 0L).executePipelined(c -> { });

        verify(pool).invalidateObject(conn);
    }

    @Test
    void borrowThrowing_touchesNoConnection() throws Exception {
        var pool = pool();
        when(pool.borrowObject()).thenThrow(new IllegalStateException("pool exhausted"));

        assertThatThrownBy(() -> new LettuceRedisExecutor(null, pool, false, () -> 0L)
                .executePipelined(c -> { }))
                .isInstanceOf(IllegalStateException.class);

        verify(pool, never()).returnObject(org.mockito.ArgumentMatchers.any());
        verify(pool, never()).invalidateObject(org.mockito.ArgumentMatchers.any());
    }
}
