package com.peripheral.camera;

public class CameraServiceException extends Exception {

    public CameraServiceException(String message) {
        super(message);
    }

    public CameraServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
