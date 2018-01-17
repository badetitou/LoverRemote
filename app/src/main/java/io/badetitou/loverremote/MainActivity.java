package io.badetitou.loverremote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;
import java.util.UUID;

import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static io.badetitou.loverremote.BluetoothHelper.sixteenBitUuid;

public class MainActivity extends AppCompatActivity {

    BluetoothGattCharacteristic receiveCharacteristic;

    BluetoothAdapter mBluetoothAdapter;
    BluetoothDevice mDevice;
    BluetoothGatt mBluetoothGatt;
    BluetoothGattService mBluetoothGattService;

    private String TAG = "BLUE";

    TextView temperature;
    TextView hydrometry;
    TextView pressure;
    TextView bluetooth;


    private Metrics currentMetrics = Metrics.Hydro;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE NOT SUPPORTED", Toast.LENGTH_SHORT).show();
            finish();
        }

        TextView t = findViewById(R.id.connected);
        temperature = findViewById(R.id.temperature);
        hydrometry = findViewById(R.id.hydro);
        pressure = findViewById(R.id.pressure);

        bluetooth = findViewById(R.id.bluetooth);
        ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        if (mWifi.isConnected()) {
            t.setText("Connected to wifi");
        } else { t.setText("No connected to WIFI"); }

        final BluetoothManager bluetoothManager =  (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
        }

        bluetooth.setText("Bluetooth available");

        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            bluetooth.setText("Device found");
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals("Timer"))
                    mDevice = device;
            }
        }

        mBluetoothGatt = mDevice.connectGatt(this, false, mGattCallback);

        bluetooth.setText("Connection to " + mDevice.getName());

    }

    private final BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i("TAG", "Connected to GATT server.");
                        Log.i("TAG", "Attempting to start service discovery:" + mBluetoothGatt.discoverServices());
                    } else if (newState == STATE_DISCONNECTED) {
                        Log.i("TAG", "Disconnected from GATT server. Error : " + status);
                    }
                }

                @Override
                // New services discovered
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG,"Service discovered : " + gatt.getServices());
                        mBluetoothGattService = gatt.getService(UUID_SERVICE);
                        receiveCharacteristic = mBluetoothGattService.getCharacteristic(UUID_RECEIVE);
                        Log.i(TAG, "UUID " + receiveCharacteristic.getUuid().toString());

                        mBluetoothGatt.setCharacteristicNotification(receiveCharacteristic, true);
                        BluetoothGattDescriptor descriptor = receiveCharacteristic.getDescriptor(UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        mBluetoothGatt.writeDescriptor(descriptor);


                    } else {
                        Log.w("TAG", "onServicesDiscovered received: " + status);
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt,
                                                    BluetoothGattCharacteristic characteristic) {
                    broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
                    Log.d(TAG, "Changed");
                }
            };

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile. Data
        // parsing is carried out as per profile specifications.
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
        } else {
            // For all other profiles, writes the data formatted in HEX.
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                StringBuilder stringBuilder = new StringBuilder(data.length);
                if (analyseData(data, stringBuilder)) {
                    return;
                }
                stringBuilder = new StringBuilder(data.length);
                for(byte byteChar : data)
                    //stringBuilder.append(String.format("%02X ", byteChar));
                    stringBuilder.append(String.format("%s ", byteChar));
                intent.putExtra(EXTRA_DATA, stringBuilder.toString());
                Log.i(TAG, "SEND DATA : " + data);
            }
        }
        sendBroadcast(intent);
    }

    private boolean analyseData(byte[] bytes, StringBuilder stringBuilder) {
        try {
            for (byte byteChar : bytes)
                stringBuilder.append(String.format("%c ", byteChar));
            switch (stringBuilder.toString()) {
                case "t ":
                    currentMetrics = Metrics.Temperature;
                    return true;
                case "h ":
                    currentMetrics = Metrics.Hydro;
                    return true;
                case "p ":
                    currentMetrics = Metrics.Pressure;
                    return true;
            }
            return false;
        } catch (Exception e){
            return false;
        }
    }


    public final static UUID UUID_SEND = sixteenBitUuid(0x2222);
    public final static UUID UUID_RECEIVE = sixteenBitUuid(0x2221);
    public final static UUID UUID_SERVICE = sixteenBitUuid(0x2220);
    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "io.badetitou.sex.EXTRA_DATA";

    public final static UUID UUID_HEART_RATE_MEASUREMENT = UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);



    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("DataReceived ", intent.getStringExtra(EXTRA_DATA));
            updateDisplay(intent.getStringExtra(EXTRA_DATA));
        }
    };


    private void updateDisplay(String s){
        switch (currentMetrics){
            case Hydro:
                hydrometry.setText(s);
                break;
            case Pressure:
                pressure.setText(s);
                break;
            case Temperature:
                temperature.setText(s);
                break;
            default:
                bluetooth.setText("error ? ");
        }
    }

    @Override
    public void onStop(){
        super.onStop();
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_GATT_CONNECTED);
        intentFilter.addAction(ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private final String TAG_BUZZ = "BUZZ";

    public void buzz(View view) {
        BluetoothGattCharacteristic characteristic = mBluetoothGattService.getCharacteristic(UUID_SEND);

        if (characteristic == null) {
            Log.w(TAG_BUZZ, "Send characteristic not found");
        } else {

            characteristic.setValue("He");
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (!mBluetoothGatt.writeCharacteristic(characteristic))
                Log.w(TAG_BUZZ, "Send don't work");
            Log.i(TAG_BUZZ, "Message send");
        }
    }
}

