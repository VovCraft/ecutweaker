package org.quark.dr.canapp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;

import org.quark.dr.usbserial.driver.UsbSerialDriver;
import org.quark.dr.usbserial.driver.UsbSerialPort;
import org.quark.dr.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ElmUsbSerial extends ElmBase {
    private static final String ACTION_USB_PERMISSION = "org.quark.dr.canapp.USB_PERMISSION";
    private static UsbSerialPort msPort = null;
    private static UsbSerialPort mPortNeedingPermission = null;
    private static PendingIntent mPermissionIntent;
    private final Context mContext;
    private ConnectedThread mConnectedThread;
    private String mUsbSerial;

    ElmUsbSerial(Context context, Handler handler, String logDir) {
        super(handler, logDir);
        mContext = context;
        mPermissionIntent = PendingIntent.getBroadcast(context,
                0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public int getMode() {
        return MODE_USB;
    }

    @Override
    public boolean connect(String serial) {
        setState(STATE_DISCONNECTED);
        mUsbSerial = serial;
        msPort = null;
        mPortNeedingPermission = null;
        final UsbManager usbManager = (UsbManager) mContext.getApplicationContext().getSystemService(Context.USB_SERVICE);

        if (usbManager == null) {
            logInfo("USB : UsbManager is null");
            return false;
        }

        final List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        if (drivers.isEmpty()) {
            logInfo("USB : No USB serial devices found");
            return false;
        }

        logInfo("USB : Found " + drivers.size() + " USB driver(s)");
        
        // Log all available devices for debugging
        logInfo("USB : Looking for device with serial: '" + serial + "'");
        int deviceIndex = 0;
        for (final UsbSerialDriver driver : drivers) {
            deviceIndex++;
            logInfo("USB : Device " + deviceIndex + " - Driver: " + driver.getClass().getSimpleName() + 
                    ", VendorId: 0x" + Integer.toHexString(driver.getDevice().getVendorId()) + 
                    ", ProductId: 0x" + Integer.toHexString(driver.getDevice().getProductId()) +
                    ", Ports: " + driver.getPorts().size());
        }

        // Special case: if serial is empty/null and only one device, use it
        boolean useFirstDevice = (serial == null || serial.isEmpty()) && drivers.size() == 1;
        if (useFirstDevice) {
            logInfo("USB : Serial is empty and only one device found, will use it");
        }

        final List<UsbSerialPort> result = new ArrayList<>();
        for (final UsbSerialDriver driver : drivers) {
            final List<UsbSerialPort> ports = driver.getPorts();
            result.addAll(ports);
            for (UsbSerialPort port : ports) {
                try {
                    // Check permission first, before trying to get serial
                    if (!usbManager.hasPermission(port.getDriver().getDevice())) {
                        logInfo("USB : Found device without permission, requesting...");
                        mPortNeedingPermission = port;
                        usbManager.requestPermission(port.getDriver().getDevice(), mPermissionIntent);
                        continue; // Check other devices, but remember this one needs permission
                    }
                    
                    // We have permission, try to open
                    UsbDeviceConnection connection = usbManager.openDevice(port.getDriver().getDevice());
                    if (connection == null) {
                        logInfo("USB : error opening device connection (connection is null)");
                        continue;
                    }
                    
                    // Open the port to get serial number
                    try {
                        port.open(connection);
                        
                        // Now get the serial number (requires open connection)
                        String portSerial = port.getSerial();
                        if (portSerial == null) portSerial = "";
                        logInfo("USB : Opened device with serial: '" + portSerial + "'");
                        
                        // Check if this is the device we're looking for
                        // Match by serial OR if both are empty/null and only one device exists
                        boolean isMatch = portSerial.equals(mUsbSerial) || 
                                         (useFirstDevice && (portSerial.isEmpty() || mUsbSerial.isEmpty()));
                        
                        if (isMatch) {
                            msPort = port;
                            logInfo("USB : Found matching device!");
                            // Store the actual serial for future use
                            if (!portSerial.isEmpty() && mUsbSerial.isEmpty()) {
                                mUsbSerial = portSerial;
                            }
                            break;
                        } else {
                            // Not the right device, close it
                            logInfo("USB : Not the device we're looking for (wanted: '" + mUsbSerial + "'), closing");
                            port.close();
                        }
                    } catch (IOException e) {
                        // Failed to open, close the connection
                        try {
                            connection.close();
                        } catch (Exception ce) {
                            // Ignore close errors
                        }
                        logInfo("USB : IOException opening port: " + e.getClass().getName() + " - " + e.getMessage());
                        if (e.getCause() != null) {
                            logInfo("USB : Caused by: " + e.getCause().getMessage());
                        }
                    }
                } catch (Exception e) {
                    logInfo("USB : Exception accessing device: " + e.getClass().getName() + " - " + e.getMessage());
                    if (e.getCause() != null) {
                        logInfo("USB : Caused by: " + e.getCause().getMessage());
                    }
                }
            }
            if (msPort != null) {
                break;
            }
        }

        if (msPort == null) {
            if (mPortNeedingPermission != null) {
                logInfo("USB : Waiting for user to grant permission");
            } else {
                logInfo("USB : Could not find or open USB device with serial: " + serial);
            }
            return false;
        }

        try {
            msPort.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            try {
                msPort.close();
            } catch (IOException e2) {

            }
            logInfo("USB : error setting port parameters" + e.getMessage());
            msPort = null;
            return false;
        } catch (Exception e) {
            logInfo("USB : error setting port parameters : " + e.getMessage());
        }
        logInfo("USB : Interface successfully connected");
        // Launch thread
        mConnectedThread = new ConnectedThread(msPort);
        mConnectedThread.start();
        setState(STATE_CONNECTED);
        return true;
    }

    @Override
    public boolean reconnect() {
        disconnect();
        return connect(mUsbSerial);
    }

    @Override
    public void disconnect() {
        if (mConnectedThread != null)
            mConnectedThread.cancel();

        clearMessages();

        synchronized (this) {
            if (mConnectionHandler != null) {
                mConnectionHandler.removeCallbacksAndMessages(null);
            }
        }
        mConnecting = false;

        setState(STATE_NONE);
    }

    @Override
    protected String writeRaw(String raw_buffer) {
        raw_buffer += "\r";
        return mConnectedThread.write(raw_buffer.getBytes());
    }

    private void connectionLost(String message) {
        // Send a failure message back to the Activity;
        logInfo("USB device connection was lost : " + message);
        mRunningStatus = false;
        setState(STATE_DISCONNECTED);
    }

    @Override
    public boolean hasDevicePermission() {
        final UsbManager usbManager = (UsbManager) mContext.getApplicationContext().getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return false;
        
        // Check the connected port first
        if (msPort != null) {
            return usbManager.hasPermission(msPort.getDriver().getDevice());
        }
        
        // Check the port that needs permission
        if (mPortNeedingPermission != null) {
            return usbManager.hasPermission(mPortNeedingPermission.getDriver().getDevice());
        }
        
        // No port to check
        return true;
    }

    @Override
    public void requestPermission() {
        final UsbManager usbManager = (UsbManager) mContext.getApplicationContext().getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;
        
        // Request permission for the port that needs it
        if (mPortNeedingPermission != null) {
            logInfo("USB : Requesting permission for device");
            usbManager.requestPermission(mPortNeedingPermission.getDriver().getDevice(), mPermissionIntent);
        } else if (msPort != null) {
            logInfo("USB : Requesting permission for connected device");
            usbManager.requestPermission(msPort.getDriver().getDevice(), mPermissionIntent);
        } else {
            logInfo("USB : No device to request permission for");
        }
    }

    /*
     * Connected thread class
     * Asynchronously manage ELM connection
     *
     */
    private class ConnectedThread extends Thread {
        private final UsbSerialPort mUsbSerialPort;

        public ConnectedThread(UsbSerialPort usbSerialPort) {
            mUsbSerialPort = usbSerialPort;
        }

        public void run() {
            connectedThreadMainLoop();
        }

        public String write(byte[] buffer) {
            writeToElm(buffer);
            return readFromElm();
        }

        public void writeToElm(byte[] buffer) {
            try {
                if (mUsbSerialPort != null) {
                    byte[] arrayOfBytes = buffer;
                    mUsbSerialPort.write(arrayOfBytes, 500);
                }
            } catch (Exception localIOException1) {
                connectionLost("USBWRITE IO Exception : " + localIOException1.getMessage());
                try {
                    mUsbSerialPort.close();
                } catch (IOException e) {

                }
            }
        }

        public String readFromElm() {
            StringBuilder final_res = new StringBuilder();
            while (true) {
                byte[] bytes = new byte[2048];
                int bytes_count = 0;
                long millis = System.currentTimeMillis();
                if (mUsbSerialPort != null) {
                    try {
                        bytes_count = mUsbSerialPort.read(bytes, 1500);
                    } catch (IOException e) {
                        logInfo("USB read IO exception : " + e.getMessage());
                        bytes_count = 0;
                    } catch (NullPointerException pne) {
                        connectionLost("USB read exception (closing) : " + pne.getMessage());
                        break;
                    } catch (Exception e) {
                        logInfo("USB read exception : " + e.getMessage());
                    }

                    if (bytes_count > 0) {
                        boolean eof = false;
                        String res = new String(bytes, 0, bytes_count);
                        res = res.substring(0, bytes_count);

                        if (res.length() > 0) {
                            // Only break when ELM has sent termination char
                            if (res.charAt(res.length() - 1) == '>') {
                                if (res.length() > 2)
                                    res = res.substring(0, res.length() - 2);
                                else
                                    res = "";
                                eof = true;
                            }
                            res = res.replaceAll("\r", "\n");
                            final_res.append(res);
                            if (eof)
                                break;
                        }
                    } else {
                        try {
                            Thread.sleep(5);
                        } catch (Exception e) {

                        }
                    }
                    if ((System.currentTimeMillis() - millis) > 4000) {
                        connectionLost("USB read : Timeout");
                        break;
                    }

                }
            }
            return final_res.toString();
        }

        public void cancel() {
            mRunningStatus = false;
            interrupt();

            try {
                mUsbSerialPort.close();
            } catch (IOException e) {
            }

            try {
                join();
            } catch (InterruptedException e) {
            }
        }
    }
}
