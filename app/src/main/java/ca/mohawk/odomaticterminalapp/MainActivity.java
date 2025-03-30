package ca.mohawk.odomaticterminalapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Odomatic";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothManager bluetoothManager;

// Always check if the adapter is null before proceeding.

    private Button btnScan;
    private ListView listDevices;

    // For discovered devices
    private ArrayList<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private ArrayAdapter<String> devicesAdapter;

    // Flag to track if BroadcastReceiver is registered
    private boolean isReceiverRegistered = false;


    private ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(MainActivity.this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Bluetooth enabling canceled", Toast.LENGTH_SHORT).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnScan = findViewById(R.id.btnScan);
        listDevices = findViewById(R.id.listDevices);

        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth.
            Toast.makeText(this, "Bluetooth not supported!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Make sure Bluetooth is on, or prompt the user
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            // For Android 12+ check BLUETOOTH_CONNECT permission before launching
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_PERMISSIONS
                );
                return;
            }
            enableBluetoothLauncher.launch(enableBtIntent);
        }

        // Request needed runtime permissions
        checkAndRequestPermissions();

        // Setup list adapter
        devicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listDevices.setAdapter(devicesAdapter);

        // On user tap, launch SendCommandActivity with the chosen device address
        listDevices.setOnItemClickListener((parent, view, position, id) -> {
            BluetoothDevice chosenDevice = discoveredDevices.get(position);

            if (chosenDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                Log.d(TAG, "Attempting to pair with " + chosenDevice.getName());
                chosenDevice.createBond();
            }

            Intent intent = new Intent(MainActivity.this, SendCommandActivity.class);
            intent.putExtra("device_address", chosenDevice.getAddress());
            startActivity(intent);
        });

        // On scan button, start Bluetooth discovery
        btnScan.setOnClickListener((View v) -> startBluetoothDiscovery());
    }

    /**
     * Check & request needed runtime permissions
     */
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION // for older device scanning
                        },
                        REQUEST_PERMISSIONS
                );
            }
        } else {
            // For pre-Android 12 devices, location permission is required for discovery
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_PERMISSIONS
                );
            }
        }
    }

    /**
     * Handle the result of the permission request or Bluetooth enable request
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // If user denies permission, scanning won't work.
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Bluetooth enabling canceled", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Start the device discovery (scan) process
     */
    private void startBluetoothDiscovery() {
        // Clear previous results
        discoveredDevices.clear();
        devicesAdapter.clear();

        // Show already paired devices that contain "OBD" in their name
        listBondedDevices();

        // Check BLUETOOTH_SCAN permission on Android 12+
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions();
            return;
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // Register receiver for new devices
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(mReceiver, filter);
        isReceiverRegistered = true;

        bluetoothAdapter.startDiscovery();
        Toast.makeText(this, "Scanning for OBD devices...", Toast.LENGTH_SHORT).show();
    }

    /**
     * List bonded (already paired) devices with "OBD" in their name
     */
    private void listBondedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return; // can't proceed
            }
        }

        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            String deviceName = device.getName();
            if (deviceName == null) {
                deviceName = "Unknown (Paired)";
            }
            // We only add devices whose names contain "OBD" (ignore case)
            if (deviceName.toUpperCase().contains("OBD") || deviceName.toUpperCase().contains("ELM")) {
                String listItem = deviceName + " (Paired)";
                discoveredDevices.add(device);
                devicesAdapter.add(listItem);
            }
        }
    }

    /**
     * BroadcastReceiver to handle discovered devices
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class
                    );
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (device != null) {
                    // For Android 12+, need BLUETOOTH_CONNECT to get name
                    String deviceName = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                                == PackageManager.PERMISSION_GRANTED) {
                            deviceName = device.getName();
                        }
                    } else {
                        deviceName = device.getName();
                    }
                    if (deviceName == null) {
                        deviceName = "Unknown";
                    }

                    // Filter by "OBD" ignoring case
                    if (deviceName.toUpperCase().contains("OBD")|| deviceName.toUpperCase().contains("ELM")) {
                        Log.d(TAG, "Found OBD device: " + deviceName);

                        if(device.getBondState() != BluetoothDevice.BOND_BONDED) {
                            discoveredDevices.add(device);
                            String listItem = deviceName ;
                            runOnUiThread(() -> devicesAdapter.add(listItem));
                        }
                    }
                }
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister receiver if needed
        stopDiscovery();
    }

    private void stopDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter.cancelDiscovery();
                }
            } else {
                bluetoothAdapter.cancelDiscovery();
            }
        }
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(mReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Receiver not registered: " + e.getMessage());
            }
            isReceiverRegistered = false;
        }
    }
}