package com.mostafatamer.trysomethingcrazy.errorHandlers;

import com.mostafatamer.trysomethingcrazy.domain.ApiError;
import com.mostafatamer.trysomethingcrazy.domain.ApiResponse;
import com.mostafatamer.trysomethingcrazy.exceptions.ClientException;
import lombok.extern.java.Log;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
@Log
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception exp) {
        exp.printStackTrace();
        return ApiResponse.builder()
                .apiError(ApiError.builder()
                        .message(exp.getMessage())
                        .build())
                .build();
    }

    @ExceptionHandler(ClientException.class)
    public ApiResponse<?> handleClientException(ClientException exp) {
        exp.printStackTrace();
        return ApiResponse.builder()
                .apiError(ApiError.builder()
                        .clientMessage(exp.getMessage())
                        .build())
                .build();
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ApiResponse<?> handleMethodArgumentNotValidException(HandlerMethodValidationException exp) {
        exp.printStackTrace();
        return ApiResponse.builder()
                .apiError(ApiError.builder()
                        .message(exp.getMessage())
                        .validationMessages(exp.getAllErrors().stream()
                                .map(MessageSourceResolvable::getDefaultMessage)
                                .toList())
                        .build())
                .build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException exp) {
        exp.printStackTrace();

        return ApiResponse.builder()
                .apiError(ApiError.builder()
                        .message(exp.getMessage())
                        .validationMessages(exp.getBindingResult().getAllErrors().stream()
                                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                                .toList())
                        .build())
                .build();
    }
}

