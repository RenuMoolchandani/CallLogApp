package com.example.calllogapp;

import android.Manifest;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.CallLog;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import androidx.appcompat.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

class CallLogItem {
    private String contactName;
    private String phoneNumber;
    private String callType;
    private String callDate;
    private String callDuration;
    private boolean isSynced;

    public CallLogItem(String contactName, String phoneNumber, String callType, String callDate, String callDuration, boolean isSynced) {
        this.contactName = contactName;
        this.phoneNumber = phoneNumber;
        this.callType = callType;
        this.callDate = callDate;
        this.callDuration = callDuration;
        this.isSynced = isSynced;
    }

    // Getters and setters for each field
    public String getContactName() {
        return contactName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getCallType() {
        return callType;
    }

    public String getCallDate() {
        return callDate;
    }

    public String getCallDuration() {
        return callDuration;
    }

    public boolean isSynced() {
        return isSynced;
    }

    public void setSynced(boolean synced) {
        isSynced = synced;
    }
}


public class MainActivity extends Activity {

    private static final int PERMISSIONS_REQUEST_READ_CALL_LOG = 1;
    private static final String PREFERENCES_FILE = "com.example.calllogapp.preferences";
    private static final String API_URL_KEY = "api_url";

    private EditText apiUrlInput;
    private Button saveButton, loadLogsButton;
    private SearchView searchView;
    private ListView callLogListView;
    private CallLogAdapter callLogAdapter;
    private List<CallLogItem> callLogList = new ArrayList<>();
    private String apiUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        apiUrlInput = findViewById(R.id.apiUrlInput);
        saveButton = findViewById(R.id.saveButton);
        loadLogsButton = findViewById(R.id.loadLogsButton);
        searchView = findViewById(R.id.searchView);
        callLogListView = findViewById(R.id.callLogListView);

        // Load saved API URL from shared preferences
        SharedPreferences sharedPreferences = getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
        apiUrl = sharedPreferences.getString(API_URL_KEY, "");
        apiUrlInput.setText(apiUrl);

        // Set save button listener to store API URL locally
        saveButton.setOnClickListener(v -> {
            apiUrl = apiUrlInput.getText().toString().trim();
            if (!apiUrl.isEmpty()) {
                sharedPreferences.edit().putString(API_URL_KEY, apiUrl).apply();
                Toast.makeText(MainActivity.this, "API URL saved", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "Please enter a valid API URL", Toast.LENGTH_SHORT).show();
            }
        });

        // Set load logs button listener to fetch and display call logs
        loadLogsButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_CALL_LOG}, PERMISSIONS_REQUEST_READ_CALL_LOG);
            } else {
                loadCallLogs();
            }
        });

        // Search functionality
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                callLogAdapter.filter(newText);
                return true;
            }
        });

        // Initialize adapter for ListView
        callLogAdapter = new CallLogAdapter(this, callLogList);
        callLogListView.setAdapter(callLogAdapter);

        // Start service to listen for call end events and send data to the API
        startService(new Intent(this, CallListenerService.class));
    }

    // Request permission to read call logs
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_READ_CALL_LOG) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadCallLogs();
            } else {
                Toast.makeText(this, "Permission denied to read call logs", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Fetch call logs from the device and display them in ListView
    private void loadCallLogs() {
        callLogList.clear();


        // Specify the columns to retrieve from the call log
        String[] projection = new String[]{
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };

// Query the call log with the specified projection
        Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC");

        if (cursor != null) {
            int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
            int nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
            int typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);
            int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);
            int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);

            while (cursor.moveToNext()) {
                String number = cursor.getString(numberIndex);
                String name = cursor.getString(nameIndex);
                String callType = cursor.getString(typeIndex);
                String callDate = cursor.getString(dateIndex);
                String callDuration = cursor.getString(durationIndex);

                // Add the call log to the list
                CallLogItem logItem = new CallLogItem(name != null ? name : number, number, callType, callDate, callDuration, false);
                callLogList.add(logItem);
            }
            cursor.close();
        }


        callLogAdapter.notifyDataSetChanged();
    }

    // CallListenerService for monitoring calls and sending data to API
    public static class CallListenerService extends Service {

        private TelephonyManager telephonyManager;

        @Override
        public void onCreate() {
            super.onCreate();

            telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

            // Listen for call state changes
            telephonyManager.listen(new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) {
                        // Call ended, send the data to API
                        sendCallDataToAPI(incomingNumber);
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }

        private void sendCallDataToAPI(String number) {
            try {
                URL url = new URL("http://your-api-url.com");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");

                // Create the data to send
                JSONObject callData = new JSONObject();
                callData.put("mobile_number", number);
                callData.put("call_date", System.currentTimeMillis());
                callData.put("call_type", "OUTGOING");  // Modify based on actual data
                callData.put("call_duration", 30); // Replace with actual duration

                // Send data
                OutputStream os = conn.getOutputStream();
                os.write(callData.toString().getBytes());
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d("CallListenerService", "Response Code: " + responseCode);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }

    // CallLogAdapter for displaying call logs in the ListView
    public static class CallLogAdapter extends ArrayAdapter<CallLogItem> {

        private List<CallLogItem> callLogList;

        public CallLogAdapter(Context context, List<CallLogItem> logs) {
            super(context, 0, logs);
            this.callLogList = logs;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CallLogItem logItem = getItem(position);

            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.call_log_item, parent, false);
            }

            TextView nameView = convertView.findViewById(R.id.contactName);
            ImageView typeIcon = convertView.findViewById(R.id.callTypeIcon);
            TextView durationView = convertView.findViewById(R.id.callDuration);
            TextView dateView = convertView.findViewById(R.id.callDate);

            nameView.setText(logItem.getContactName());
            durationView.setText(logItem.getCallDuration());
            dateView.setText(logItem.getCallDate());

            // Set call type icon based on call type
            if (logItem.getCallType().equals("INCOMING")) {
                typeIcon.setImageResource(R.drawable.incomming_call);
            } else if (logItem.getCallType().equals("OUTGOING")) {
                typeIcon.setImageResource(R.drawable.outgoing_call);
            } else {
                typeIcon.setImageResource(R.drawable.missed_call);
            }

            return convertView;
        }

        // Filtering method for search functionality
        public void filter(String text) {
            // Filtering logic here based on
            List<CallLogItem> filteredList = new ArrayList<>();
            for (CallLogItem item : callLogList) {
                if (item.getContactName().toLowerCase().contains(text.toLowerCase())) {
                    filteredList.add(item);
                }
            }
            clear();
            addAll(filteredList);
            notifyDataSetChanged();
        }
    }
}
