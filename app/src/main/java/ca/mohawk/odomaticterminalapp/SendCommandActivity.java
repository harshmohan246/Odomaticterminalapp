package ca.mohawk.odomaticterminalapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class SendCommandActivity extends AppCompatActivity {
    private static final String TAG = "OBD_SendCmd";
    private static final UUID OBD_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextView tvStatus, tvResponse;
    private EditText etCommand;
    private Button btnSendCmd;
    BluetoothManager bluetoothManager;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outStream;
    private InputStream inStream;
    protected String receivedMessage, msgTemp,deviceAddr;
    private Thread readThread;
    private volatile boolean stopReading = false;
    private volatile boolean isReconnecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_command);

        tvStatus = findViewById(R.id.tvStatus);
        etCommand = findViewById(R.id.etCommand);
        btnSendCmd = findViewById(R.id.btnSendCmd);
        tvResponse = findViewById(R.id.tvResponse);


        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        // Get device address from Intent
        String deviceAddress = getIntent().getStringExtra("device_address");
        if (deviceAddress == null) {
            Toast.makeText(this, "No device address provided!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Attempt to connect in a background thread

        deviceAddr = deviceAddress;
        connectToDevice(deviceAddress);

        btnSendCmd.setOnClickListener(view -> {
            String cmd = etCommand.getText().toString().trim();
            if (!cmd.isEmpty()) {
                // ELM327 typically wants commands ending with \r
                sendOBDCommand(cmd);
            }
            etCommand.setText("");
        });
    }

    private void connectToDevice(String address) {
        new Thread(() -> {
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter == null) {
                runOnUiThread(() -> {
                    Toast.makeText(SendCommandActivity.this,
                            "Bluetooth not supported on this device",
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }

            // For Android 12+, check BLUETOOTH_CONNECT permission before calling getRemoteDevice().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        SendCommandActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
                ) {
                    runOnUiThread(() -> {
                        Toast.makeText(SendCommandActivity.this,
                                "Missing BLUETOOTH_CONNECT permission!",
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
            }

            // Now safely call getRemoteDevice
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device == null) {
                runOnUiThread(() -> {
                    Toast.makeText(SendCommandActivity.this,
                            "Device not found!",
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }

            // Attempt to create socket and connect...
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(OBD_UUID);
                bluetoothSocket.connect();
                outStream = bluetoothSocket.getOutputStream();
                inStream = bluetoothSocket.getInputStream();

                runOnUiThread(() -> {
                    tvStatus.setText("Connected to: " + device.getName());
                    startReading();
                    Toast.makeText(SendCommandActivity.this,
                            "Connected to " + device.getName(),
                            Toast.LENGTH_SHORT).show();
                });



            } catch (IOException e) {
                Log.e(TAG, "Error connecting", e);
                runOnUiThread(() -> {
                    tvStatus.setText("Connection failed");
                    Toast.makeText(SendCommandActivity.this,
                            "Connection failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
                scheduleReconnection();
            }
        }).start();
    }


    private void startReading() {
        stopReading = false;
        readThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            int bytes;
            while (!stopReading) {
                try {
                    if (inStream != null && (bytes = inStream.read(buffer)) > 0) {
                        final String msg = new String(buffer, 0, bytes);
                        msgTemp += msg;
                        if (msg.contains(">")) {
                            receivedMessage = msgTemp;
                            msgTemp = "";
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Reading error, attempting reconnection", e);
                    scheduleReconnection();
                    break;
                }
            }
        });
        readThread.start();
    }

    private void scheduleReconnection() {
        if (isReconnecting) {
            return;
        }
        isReconnecting = true;
        // Use a Handler to post a delayed reconnection attempt.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Run reconnection attempt on a background thread.
            new Thread(() -> {
                // Clean up existing resources.
                closeConnection();
                connectToDevice(deviceAddr);
                isReconnecting = false;
            }).start();
        }, 5000);
    }

    private void sendOBDCommand(String command) {
        command += "\r";
        receivedMessage = "";
        if (outStream != null) {
            try {
                outStream.write(command.getBytes());
                outStream.flush();
            } catch (IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Send error: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        } else {
            Toast.makeText(this, "Not connected!", Toast.LENGTH_SHORT).show();
        }
        while (receivedMessage.isEmpty()) {
            try {
                Thread.sleep(100);
            } catch (Exception ignored) {
            }
        }
        runOnUiThread(() -> {
            receivedMessage = receivedMessage.replaceAll("\\r", "\n");
            tvResponse.append("\n" + receivedMessage);

            findViewById(R.id.scrollViewResponse).post(() -> {
                ((android.widget.ScrollView) findViewById(R.id.scrollViewResponse))
                        .fullScroll(android.view.View.FOCUS_DOWN);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopReading = true;
        if (readThread != null && readThread.isAlive()) {
            readThread.interrupt();
        }
        closeConnection();
    }

    private void closeConnection() {
        try {
            if (inStream != null) inStream.close();
        } catch (Exception ignored) {}
        try {
            if (outStream != null) outStream.close();
        } catch (Exception ignored) {}
        try {
            if (bluetoothSocket != null && bluetoothSocket.isConnected()) {
                bluetoothSocket.close();
            }
        } catch (Exception ignored) {}
    }
}
