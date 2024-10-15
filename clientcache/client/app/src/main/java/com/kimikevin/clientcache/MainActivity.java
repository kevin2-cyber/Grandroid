package com.kimikevin.clientcache;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.stub.ClientCalls;

public final class MainActivity extends AppCompatActivity {
    private static final int CACHE_SIZE_IN_BYTES = 1024 * 1024; // 1MB
    private static final String TAG = "grpcCacheExample";
    private Button sendButton;
    private EditText hostEdit;
    private EditText portEdit;
    private EditText messageEdit;
    private TextView resultText;
    private CheckBox getCheckBox;
    private CheckBox noCacheCheckBox;
    private CheckBox onlyIfCachedCheckBox;
    private SafeMethodCachingInterceptor.Cache cache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        getCheckBox = findViewById(R.id.get_checkbox);
        noCacheCheckBox = findViewById(R.id.no_cache_checkbox);
        onlyIfCachedCheckBox = findViewById(R.id.only_if_cached_checkbox);
        resultText = findViewById(R.id.grpc_response_text);
        resultText.setMovementMethod(new ScrollingMovementMethod());
        cache = SafeMethodCachingInterceptor.newLruCache(CACHE_SIZE_IN_BYTES);
    }

    /** Sends RPC. Invoked when app button is pressed. */
    public void sendMessage(View view) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(hostEdit.getWindowToken(), 0);
        sendButton.setEnabled(false);
        resultText.setText("");
        new GrpcTask(this, cache)
                .execute(
                        hostEdit.getText().toString(),
                        messageEdit.getText().toString(),
                        portEdit.getText().toString(),
                        getCheckBox.isChecked(),
                        noCacheCheckBox.isChecked(),
                        onlyIfCachedCheckBox.isChecked());
    }

    private static class GrpcTask extends AsyncTask<Object, Void, String> {
        private final WeakReference<Activity> activityReference;
        private final SafeMethodCachingInterceptor.Cache cache;
        private ManagedChannel channel;

        private GrpcTask(Activity activity, SafeMethodCachingInterceptor.Cache cache) {
            this.activityReference = new WeakReference<Activity>(activity);
            this.cache = cache;
        }

        @Override
        protected String doInBackground(Object... params) {
            String host = (String) params[0];
            String message = (String) params[1];
            String portStr = (String) params[2];
            boolean useGet = (boolean) params[3];
            boolean noCache = (boolean) params[4];
            boolean onlyIfCached = (boolean) params[5];
            int port = TextUtils.isEmpty(portStr) ? 0 : Integer.parseInt(portStr);
            try {
                channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
                Channel channelToUse =
                        ClientInterceptors.intercept(
                                channel, SafeMethodCachingInterceptor.newSafeMethodCachingInterceptor(cache));
                HelloRequest request = HelloRequest.newBuilder().setName(message).build();
                HelloReply reply;
                if (useGet) {
                    MethodDescriptor<HelloRequest, HelloReply> safeCacheableUnaryCallMethod =
                            GreeterGrpc.getSayHelloMethod().toBuilder().setSafe(true).build();
                    CallOptions callOptions = CallOptions.DEFAULT;
                    if (noCache) {
                        callOptions =
                                callOptions.withOption(SafeMethodCachingInterceptor.NO_CACHE_CALL_OPTION, true);
                    }
                    if (onlyIfCached) {
                        callOptions =
                                callOptions.withOption(
                                        SafeMethodCachingInterceptor.ONLY_IF_CACHED_CALL_OPTION, true);
                    }
                    reply =
                            ClientCalls.blockingUnaryCall(
                                    channelToUse, safeCacheableUnaryCallMethod, callOptions, request);
                } else {
                    GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channelToUse);
                    reply = stub.sayHello(request);
                }
                return reply.getMessage();
            } catch (Exception e) {
                Log.e(TAG, "RPC failed", e);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                pw.flush();
                return String.format("Failed... : %n%s", sw);
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (channel != null) {
                try {
                    channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
}