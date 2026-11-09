package com.shoplite.order_service.exception;

public class ServiceUnavailableException extends RuntimeException{

    public ServiceUnavailableException(Throwable cause) {
        super("A required service is unavailable", cause);
    }
}
