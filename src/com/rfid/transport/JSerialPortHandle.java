package com.rfid.transport;

import com.fazecast.jSerialComm.SerialPort;
import com.payne.connect.port.SerialPortHandle;
import com.payne.reader.base.Consumer;
import com.payne.reader.communication.ConnectHandle;
import com.payne.reader.util.LLLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class JSerialPortHandle implements ConnectHandle {

    private final byte[] dataBuffer = new byte[1024];
    private SerialPort mSerialPort;
    private String mPort;
    private int mBaud;

    private volatile Consumer<byte[]> mConsumer;
    private volatile OutputStream mOutputStream;
    private volatile InputStream mInputStream;

    private final AtomicBoolean mIsConnected;
    private final AtomicBoolean mRunning;

    public JSerialPortHandle(String port, int baud) {
        mPort = port;
        mBaud = baud;
        mIsConnected = new AtomicBoolean(false);
        mRunning = new AtomicBoolean(true);
    }

    @Override
    public boolean onConnect() {
        synchronized (this) {
            try {
                mSerialPort = SerialPort.getCommPort(mPort);
                boolean openPort = mSerialPort.openPort();
                if (!openPort) {
                    return false;
                }
                mSerialPort.setComPortParameters(mBaud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                mOutputStream = mSerialPort.getOutputStream();
                mInputStream = mSerialPort.getInputStream();

                mRunning.set(true);
                mIsConnected.set(true);

                Thread thread = new ReceiveThread();
                thread.setName("Rfid-Read-" + SerialPortHandle.mTC.getAndIncrement());
                thread.start();
            } catch (Exception e) {
                mIsConnected.set(false);
                System.err.println("can't connect because: " + e.getMessage());
            }
            return mIsConnected.get();
        }
    }

    @Override
    public boolean isConnected() {
        return mIsConnected.get();
    }

    @Override
    public boolean onSend(byte[] bytes) {
        synchronized (this) {
            try {
                mOutputStream.write(bytes);
                return true;
            } catch (Exception e) {
                mIsConnected.set(false);
                return false;
            }
        }
    }

    @Override
    public void onReceive(Consumer<byte[]> consumer) {
        mConsumer = consumer;
    }

    @Override
    public void onDisconnect() {
        synchronized (this) {
            mRunning.set(false);
            mIsConnected.set(false);

            if (mOutputStream != null) {
                try {
                    mOutputStream.close();
                } catch (IOException ignored) {
                }
                mOutputStream = null;
            }
            if (mInputStream != null) {
                try {
                    mInputStream.close();
                } catch (IOException ignored) {
                }
                mInputStream = null;
            }
            if (mSerialPort != null) {
                mSerialPort.closePort();
                mSerialPort = null;
            }
        }
    }

    private class ReceiveThread extends Thread {

        @Override
        public void run() {
            LLLog.i("PCLog_JS_ReceiveThread_is_run");

            while (mRunning.get()) {
                if (!mIsConnected.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                try {
                    int available = mInputStream.available();
                    if (available <= 0) {
                        Thread.sleep(20);
                        continue;
                    }
                    int readLen = mInputStream.read(dataBuffer);
                    if (readLen <= 0) {
                        continue;
                    }
                    byte[] receiveBytes = new byte[readLen];
                    System.arraycopy(dataBuffer, 0, receiveBytes, 0, readLen);

                    if (mConsumer != null) {
                        mConsumer.accept(receiveBytes);
                    }
                } catch (IOException e) {
                    mIsConnected.set(false);
                    LLLog.w("PCLog_JS_ReceiveThread_is_disconnected: " + e.getMessage());
                } catch (Exception e) {
                    LLLog.w("PCLog_JS_ReceiveThread_is_error: " + e.getMessage());
                }
            }
            LLLog.w("PCLog_JS_ReceiveThread_is_finished");
        }
    }
}
