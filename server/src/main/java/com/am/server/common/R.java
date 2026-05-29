package com.am.server.common;

import java.io.Serializable;

/**
 * 统一响应体，对应设计文档第 11 章约定的 {code, message, data} 三段式
 * gz
 */
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int CODE_SUCCESS = 0;
    public static final String MSG_SUCCESS = "success";

    private int code;
    private String message;
    private T data;

    public R() {
    }

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(CODE_SUCCESS, MSG_SUCCESS, null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(CODE_SUCCESS, MSG_SUCCESS, data);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
