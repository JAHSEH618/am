package com.am.server.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerAgentPathTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest agent(String path) {
        return new MockHttpServletRequest("POST", path);
    }

    private void assertBusy(ResponseEntity<R<Void>> resp) {
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo(OverloadFailures.RETRY_AFTER_SECONDS);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.SERVER_BUSY);
    }

    @Test
    void hikariConnectionTimeoutOnAgentPathIs503NotA200With50000() {
        Exception e = new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException(
                        "aiwatch-cp - Connection is not available, request timed out after 30000ms"));
        assertBusy(handler.handleOther(e, agent("/api/v1/agent/report")));
        assertBusy(handler.handleOther(e, agent("/api/v1/agent/report-commits")));
        assertBusy(handler.handleOther(e, agent("/api/v1/agent/register")));
    }

    @Test
    void everyOverloadFamilyMapsTo503OnAgentPathIncludingWrappedCauses() {
        Exception[] overloads = {
                new SQLTransientConnectionException("Connection is not available"),
                new DataAccessResourceFailureException("x"),
                new QueryTimeoutException("x"),
                new TransactionTimedOutException("Transaction timed out"),
                new CannotAcquireLockException("Lock wait timeout exceeded"),
                new OptimisticLockingFailureException("stale"),
                new SQLException("Deadlock found", "40001", 1213),
                new SQLTimeoutException("max_execution_time"),
                new RuntimeException("outer", new RuntimeException("mid",
                        new SQLTransientConnectionException("Connection is not available"))),
        };
        for (Exception e : overloads) {
            assertBusy(handler.handleOther(e, agent("/api/v1/agent/report")));
        }
    }

    @Test
    void unrecognisedFailuresOnAgentPathAre500NotA200() {
        ResponseEntity<R<Void>> resp = handler.handleOther(new IllegalStateException("bug"), agent("/api/v1/agent/report"));
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(resp.getHeaders().getFirst("Retry-After")).as("a bug is not retry-later").isNull();

        // 约束冲突 / 数据错误重试也不会好：不是过载，仍然是 500
        ResponseEntity<R<Void>> data = handler.handleOther(new DataIntegrityViolationException("dup"),
                agent("/api/v1/agent/report"));
        assertThat(data.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void springMvcStatusExceptionsKeepTheirOwnStatusOnAgentPath() {
        ResponseEntity<R<Void>> resp = handler.handleOther(
                new HttpRequestMethodNotSupportedException("GET"), agent("/api/v1/agent/register"));
        assertThat(resp.getStatusCode().value()).isEqualTo(405);
    }

    @Test
    void consolePathBehaviourIsUnchanged() {
        // 控制台：连接池超时仍是 HTTP 200 + 50000（前端按业务码处理），查询超时仍是 200 + 50401
        MockHttpServletRequest console = new MockHttpServletRequest("GET", "/api/v1/people");
        ResponseEntity<R<Void>> pool = handler.handleOther(
                new SQLTransientConnectionException("Connection is not available"), console);
        assertThat(pool.getStatusCode().value()).isEqualTo(200);
        assertThat(pool.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(pool.getHeaders().getFirst("Retry-After")).isNull();

        ResponseEntity<R<Void>> timeout = handler.handleOther(
                new RuntimeException(new SQLException("max exec", "HY000", 3024)), console);
        assertThat(timeout.getStatusCode().value()).isEqualTo(200);
        assertThat(timeout.getBody().getCode()).isEqualTo(ErrorCode.QUERY_TIMEOUT);

        ResponseEntity<R<Void>> other = handler.handleOther(new IllegalStateException("x"), console);
        assertThat(other.getStatusCode().value()).isEqualTo(200);
        assertThat(other.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void adminAndDashboardPathsAreNotTreatedAsAgentPaths() {
        for (String path : new String[]{"/api/v1/admin/analysis/generate", "/api/v1/dashboard/overview", "/api/v1/agents"}) {
            ResponseEntity<R<Void>> resp = handler.handleOther(new CannotCreateTransactionException("x"),
                    new MockHttpServletRequest("GET", path));
            assertThat(resp.getStatusCode().value()).as(path).isEqualTo(200);
        }
    }

    @Test
    void bizExceptionOnAgentPathStillAnswers200WithItsBusinessCode() {
        // AGENT_NOT_FOUND 等业务码必须仍是 HTTP 200 + 信封：客户端靠它触发「清凭证重新注册」
        R<Void> r = handler.handleBiz(new BizException(ErrorCode.AGENT_NOT_FOUND, "agent not found"));
        assertThat(r.getCode()).isEqualTo(ErrorCode.AGENT_NOT_FOUND);
    }
}
