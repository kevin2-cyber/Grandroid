package com.kimikevin.strictmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.TrafficStats;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.os.StrictMode.VmPolicy;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;

public class MainActivity extends AppCompatActivity {
    private Button sendButton;
    private EditText hostEdit;
    private EditText portEdit;
    private EditText messageEdit;
    private TextView resultText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        VmPolicy policy =
                new StrictMode.VmPolicy.Builder()
                        .detectCleartextNetwork()
                                .penaltyListener(
                                        MoreExecutors.directExecutor(),
                                        v -> runOnUiThread(
                                                () -> new AlertDialog.Builder(MainActivity.this)
                                                        .setTitle("StrictMode Vm Violation")
                                                        .setMessage(v.getLocalizedMessage())
                                                        .show()
                                        )
                                ).penaltyLog()
                                .build();
        StrictMode.setVmPolicy(policy);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sendButton = findViewById(R.id.send_button);
        hostEdit = findViewById(R.id.host_edit_text);
        portEdit = findViewById(R.id.port_edit_text);
        messageEdit = findViewById(R.id.message_edit_text);
        resultText = findViewById(R.id.grpc_response_text);
        resultText.setMovementMethod(new ScrollingMovementMethod());
    }

    public void sendMessage(View view) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(hostEdit.getWindowToken(), 0);
        sendButton.setEnabled(false);
        resultText.setText("");
        new GrpcTask(this)
                .execute(
                        hostEdit.getText().toString(),
                        messageEdit.getText().toString(),
                        portEdit.getText().toString());
    }

    private static class GrpcTask extends AsyncTask<String, Void, String> {
        private final WeakReference<Activity> activityReference;
        private ManagedChannel channel;

        private GrpcTask(Activity activity) {
            this.activityReference = new WeakReference<Activity>(activity);
        }

        @Override
        protected String doInBackground(String... params) {
            String host = params[0];
            String message = params[1];
            String portStr = params[2];
            int port = TextUtils.isEmpty(portStr) ? 0 : Integer.parseInt(portStr);
            try {
                channel =
                        OkHttpChannelBuilder.forAddress(host, port)
                                .transportExecutor(new NetworkTaggingExecutor(0xFDD))
                                .usePlaintext()
                                .build();
                GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
                HelloRequest request = HelloRequest.newBuilder().setName(message).build();
                HelloReply reply = stub.sayHello(request);
                return reply.getMessage();
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                pw.flush();
                return String.format("Failed... : %n%s", sw);
            }
        }

        @Override
        protected void onPostExecute(String result) {
            try {
                channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Activity activity = activityReference.get();
            if (activity == null) {
                return;
            }
            TextView resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
            Button sendButton = (Button) activity.findViewById(R.id.send_button);
            resultText.setText(result);
            sendButton.setEnabled(true);
        }
    }

    private static class NetworkTaggingExecutor extends ThreadPoolExecutor {
        private int trafficStatsTag;

        NetworkTaggingExecutor(int tag) {
            super(
                    0,
                    Integer.MAX_VALUE,
                    60L,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("grpc-android-%d").build());
            trafficStatsTag = tag;
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            TrafficStats.setThreadStatsTag(trafficStatsTag);
            super.beforeExecute(t, r);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            TrafficStats.clearThreadStatsTag();
            super.afterExecute(r, t);
        }
    }
}