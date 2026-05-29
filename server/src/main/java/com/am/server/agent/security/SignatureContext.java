package com.am.server.agent.security;

/**
 * 签名校验通过后注入到请求属性中的上下文
 * Controller 可通过 {@code request.getAttribute(SignatureContext.ATTR)} 取到
 * gz
 */
public class SignatureContext {

    public static final String ATTR = "AM_SIGNATURE_CONTEXT";

    private final String agentId;
    private final String userCode;
    private final String hostHash;

    public SignatureContext(String agentId, String userCode, String hostHash) {
        this.agentId = agentId;
        this.userCode = userCode;
        this.hostHash = hostHash;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getUserCode() {
        return userCode;
    }

    public String getHostHash() {
        return hostHash;
    }
}
