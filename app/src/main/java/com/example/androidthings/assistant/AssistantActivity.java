/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.voicehat.VoiceHatDriver;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.assistant.embedded.v1alpha1.AudioInConfig;
import com.google.assistant.embedded.v1alpha1.AudioOutConfig;
import com.google.assistant.embedded.v1alpha1.ConverseConfig;
import com.google.assistant.embedded.v1alpha1.ConverseRequest;
import com.google.assistant.embedded.v1alpha1.ConverseResponse;
import com.google.assistant.embedded.v1alpha1.ConverseState;
import com.google.assistant.embedded.v1alpha1.EmbeddedAssistantGrpc;
import com.google.protobuf.ByteString;

import org.json.JSONException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import admobilize.matrix.io.MatrixDriver;
import ai.kitt.snowboy.AppResCopy;
import ai.kitt.snowboy.Constants;
import ai.kitt.snowboy.SnowboyDetect;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;

import static ai.kitt.snowboy.Constants.ACTIVE_RES;
import static ai.kitt.snowboy.Constants.ACTIVE_UMDL;

public class AssistantActivity extends Activity implements Button.OnButtonEventListener {

    static { System.loadLibrary("snowboy-detect-android"); }

    private static final String TAG = AssistantActivity.class.getSimpleName();

    // Peripheral and drivers constants.
    private static final boolean AUDIO_USE_I2S_VOICEHAT_IF_AVAILABLE = false;
    private static final boolean AUDIO_USE_MATRIX_CREATOR_IF_AVAILABLE = true;
    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;

    // Audio constants.
    private static final String PREF_CURRENT_VOLUME = "current_volume";
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int DEFAULT_VOLUME = 100;
    private static final long INTERVAL_BUTTON_PRESSED = 3000;

    private static AudioInConfig.Encoding ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16;
    private static AudioOutConfig.Encoding ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16;

    public static final int SAMPLE_BLOCK_SIZE = 1024;

    // Snowboy wakeword
    private int preVolume = -1;
    private static String strEnvWorkSpace = Constants.DEFAULT_WORK_SPACE;
    private String activeModel = strEnvWorkSpace+ACTIVE_UMDL;
    private String commonRes = strEnvWorkSpace+ACTIVE_RES;
    private SnowboyDetect detector;

    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_OUT_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    // Google Assistant API constants.
    private static final String ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com";

    // gRPC client and stream observers.
    private EmbeddedAssistantGrpc.EmbeddedAssistantStub mAssistantService;
    private StreamObserver<ConverseRequest> mAssistantRequestObserver;
    private StreamObserver<ConverseResponse> mAssistantResponseObserver =
            new StreamObserver<ConverseResponse>() {
        @Override
        public void onNext(ConverseResponse value) {
            switch (value.getConverseResponseCase()) {
                case EVENT_TYPE:
                    Log.d(TAG, "converse response event: " + value.getEventType());
                    break;
                case RESULT:
                    final String spokenRequestText = value.getResult().getSpokenRequestText();
                    mConversationState = value.getResult().getConversationState();
                    if (value.getResult().getVolumePercentage() != 0) {
                        mVolumePercentage = value.getResult().getVolumePercentage();
                        Log.i(TAG, "assistant volume changed: " + mVolumePercentage);
                        float newVolume = AudioTrack.getMaxVolume() * mVolumePercentage / 100.0f;
                        mAudioTrack.setVolume(newVolume);
                        // Update our preferences
                        SharedPreferences.Editor editor = PreferenceManager.
                                getDefaultSharedPreferences(AssistantActivity.this).edit();
                        editor.putFloat(PREF_CURRENT_VOLUME, newVolume);
                        editor.apply();
                    }
                    if (!spokenRequestText.isEmpty()) {
                        Log.i(TAG, "assistant request text: " + spokenRequestText);
                        mAssistantHandler.post(mStopAssistantRequest);
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mAssistantRequestsAdapter.add(spokenRequestText);
                            }
                        });
                    }
                    break;
                case AUDIO_OUT:
                    final ByteBuffer audioData =
                            ByteBuffer.wrap(value.getAudioOut().getAudioData().toByteArray());
                    Log.d(TAG, "converse audio size: " + audioData.remaining());
                    mAudioTrack.write(audioData, audioData.remaining(), AudioTrack.WRITE_BLOCKING);
                    if (mLed != null) {
                        try {
                            mLed.setValue(!mLed.getValue());
                        } catch (IOException e) {
                            Log.w(TAG, "error toggling LED:", e);
                        }
                    }
                    break;
                case ERROR:
                    Log.e(TAG, "converse response error: " + value.getError());
                    break;
            }
        }

        @Override
        public void onError(Throwable t) {
            Log.e(TAG, "converse error:", t);
        }

        @Override
        public void onCompleted() {
            Log.i(TAG, "assistant response finished");
//            mAssistantHandler.post(mStopAssistantRequest);
            if (mLed != null) {
                try {
                    mLed.setValue(false);
                } catch (IOException e) {
                    Log.e(TAG, "error turning off LED:", e);
                }
            }
        }
    };

    // Button emulate Handler
    private Handler mButtonEmulateHandler = new Handler();

    // Audio playback and recording objects.
    private AudioTrack mAudioTrack;
    private AudioRecord mAudioRecord;
    private int mVolumePercentage = DEFAULT_VOLUME;

    // Hardware peripherals.
    private VoiceHatDriver mVoiceHat;
    private Button mButton;
    private Gpio mLed;
    private MatrixDriver matrix;

    // Assistant Thread and Runnables implementing the push-to-talk functionality.
    private ByteString mConversationState = null;
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;
    private Runnable mStartAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "starting assistant request");
            mAssistantRequestObserver = mAssistantService.converse(mAssistantResponseObserver);
                ConverseConfig.Builder converseConfigBuilder =
                        ConverseConfig.newBuilder()
                                .setAudioInConfig(AudioInConfig.newBuilder()
                                        .setEncoding(ENCODING_INPUT)
                                        .setSampleRateHertz(SAMPLE_RATE)
                                        .build())
                                .setAudioOutConfig(AudioOutConfig.newBuilder()
                                        .setEncoding(ENCODING_OUTPUT)
                                        .setSampleRateHertz(SAMPLE_RATE)
                                        .setVolumePercentage(mVolumePercentage)
                                        .build());
            if (mConversationState != null) {
                converseConfigBuilder.setConverseState(
                        ConverseState.newBuilder()
                                .setConversationState(mConversationState)
                                .build());
            }
            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder()
                    .setConfig(converseConfigBuilder.build())
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };

    private Runnable mStreamAssistantRequest = new Runnable() {
        @Override
        public void run() {
            ByteBuffer audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE);
            int result =
                    mAudioRecord.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING);
            if (result < 0) {
                Log.e(TAG, "error reading from audio stream:" + result);
                return;
            }
//            Log.d(TAG, "streaming ConverseRequest: " + result);
            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };

    private Runnable mStopAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "ending assistant request");
            mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            if (mAssistantRequestObserver != null) {
                mAssistantRequestObserver.onCompleted();
                mAssistantRequestObserver = null;
            }
            mAudioRecord.stop();
            matrix.micArray.stop();
            mAudioTrack.play();
            matrix.everloop.drawProgress(34);
            matrix.everloop.write();
            matrix.micArray.resume();
            mAudioRecord.startRecording();
            mAssistantHandler.post(mSnowboyDetectRequest);
        }
    };
    private Handler mMainHandler;

    // List & adapter to store and display the history of Assistant Requests.
    private ArrayList<String> mAssistantRequests = new ArrayList<>();
    private ArrayAdapter<String> mAssistantRequestsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "starting assistant demo");

        setContentView(R.layout.activity_main);
        ListView assistantRequestsListView = (ListView)findViewById(R.id.assistantRequestsListView);

        AppResCopy.copyResFromAssetsToSD(this);

        mAssistantRequestsAdapter =
                new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                        mAssistantRequests);
        assistantRequestsListView.setAdapter(mAssistantRequestsAdapter);
        mMainHandler = new Handler(getMainLooper());

        mAssistantThread = new HandlerThread("assistantThread");
        mAssistantThread.start();
        mAssistantHandler = new Handler(mAssistantThread.getLooper());

        try {
            if (AUDIO_USE_I2S_VOICEHAT_IF_AVAILABLE) {
                PeripheralManagerService pioService = new PeripheralManagerService();
                List<String> i2sDevices = pioService.getI2sDeviceList();
                if (i2sDevices.size() > 0) {
                    try {
                        Log.i(TAG, "creating voice hat driver");
                        mVoiceHat = new VoiceHatDriver(
                                BoardDefaults.getI2SDeviceForVoiceHat(),
                                BoardDefaults.getGPIOForVoiceHatTrigger(),
                                AUDIO_FORMAT_STEREO
                        );
                        mVoiceHat.registerAudioInputDriver();
                        mVoiceHat.registerAudioOutputDriver();
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "Unsupported board, falling back on default audio device:", e);
                    }
                }
            }
            if (AUDIO_USE_MATRIX_CREATOR_IF_AVAILABLE){
                matrix = new MatrixDriver();
                matrix.registerAudioInputDriver();
            }
            mButton = new Button(BoardDefaults.getGPIOForButton(), Button.LogicState.PRESSED_WHEN_LOW);
            mButton.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS);
            mButton.setOnButtonEventListener(this);
            PeripheralManagerService pioService = new PeripheralManagerService();
            mLed = pioService.openGpio(BoardDefaults.getGPIOForLED());
            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
        } catch (IOException e) {
            Log.e(TAG, "error configuring peripherals:", e);
            return;
        }

        AudioManager manager = (AudioManager)this.getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.i(TAG, "setting volume to: " + maxVolume);
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, mVolumePercentage * maxVolume / 100, 0);
        int outputBufferSize = AudioTrack.getMinBufferSize(AUDIO_FORMAT_OUT_MONO.getSampleRate(),
                AUDIO_FORMAT_OUT_MONO.getChannelMask(),
                AUDIO_FORMAT_OUT_MONO.getEncoding());
        mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(outputBufferSize)
                .build();
        mAudioTrack.play();
        int inputBufferSize = AudioRecord.getMinBufferSize(AUDIO_FORMAT_IN_MONO.getSampleRate(),
                AUDIO_FORMAT_IN_MONO.getChannelMask(),
                AUDIO_FORMAT_IN_MONO.getEncoding());
        Log.i(TAG,"inputBufferSize="+inputBufferSize);
        mAudioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AUDIO_FORMAT_IN_MONO)
                .setBufferSizeInBytes(SAMPLE_BLOCK_SIZE)
                .build();
        // Set volume from preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        float initVolume = preferences.getFloat(PREF_CURRENT_VOLUME, maxVolume);
        Log.i(TAG, "setting volume to: " + initVolume);
        mAudioTrack.setVolume(initVolume);
        // Scale initial volume to be a percent.
        mVolumePercentage = Math.round(initVolume * 100.0f / maxVolume);

        ManagedChannel channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build();
        try {
            mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                    .withCallCredentials(MoreCallCredentials.from(
                            Credentials.fromResource(this, R.raw.credentials)
                    ));
        } catch (IOException|JSONException e) {
            Log.e(TAG, "error creating assistant service:", e);
        }
        // TODO: implement wakeword like Kitt.ai or sensory
//        mButtonEmulateHandler.post(mButtonEmulateRunnable);
//        mAssistantHandler.post(mStartAssistantRequest);
        mAssistantHandler.post(mSnowboyInsance);

    }

    @Override
    public void onButtonEvent(Button button, boolean pressed) {
        try {
            if (mLed != null) {
                mLed.setValue(pressed);
            }
        } catch (IOException e) {
            Log.d(TAG, "error toggling LED:", e);
        }
        if (pressed) {
            mAssistantHandler.post(mStartAssistantRequest);
        } else {
            mAssistantHandler.post(mStopAssistantRequest);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "destroying assistant demo");
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
        if (mLed != null) {
            try {
                mLed.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing LED", e);
            }
            mLed = null;
        }
        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing button", e);
            }
            mButton = null;
        }
        if (mVoiceHat != null) {
            try {
                mVoiceHat.unregisterAudioOutputDriver();
                mVoiceHat.unregisterAudioInputDriver();
                mVoiceHat.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing voice hat driver", e);
            } catch (Exception e) {
                e.printStackTrace();
            }
            mVoiceHat = null;
        }
        if (matrix != null){
            try {
                matrix.micArray.stop();
                matrix.unregisterAudioInputDriver();
                matrix.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing MATRIX hat driver", e);
            } catch (Exception e) {
                e.printStackTrace();
            }
            matrix = null;
        }
        mAssistantHandler.post(new Runnable() {
            @Override
            public void run() {
                mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            }
        });
        mAssistantThread.quitSafely();
    }

    private boolean BUTTON_TOOGLE;
    private boolean pressed;
    private Runnable mButtonEmulateRunnable = new Runnable() {
        @Override
        public void run() {
            if(pressed)return;
            if(!BUTTON_TOOGLE){
                Log.d(TAG,"[MIC] mButtonEmulate [PRESSED]");
                mAssistantHandler.post(mStartAssistantRequest);
            }else{
                Log.d(TAG,"[MIC] mButtonEmulate [RELEASED]");
                mAssistantHandler.post(mStopAssistantRequest);
                pressed=true;
            }
            BUTTON_TOOGLE=!BUTTON_TOOGLE;
            mButtonEmulateHandler.postDelayed(mButtonEmulateRunnable,INTERVAL_BUTTON_PRESSED);
        }
    };

    private void setProperVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        preVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Log.d(TAG," ----> preVolume = "+preVolume);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.d(TAG," ----> maxVolume = "+maxVolume);
        int properVolume = (int) ((float) maxVolume * 0.2);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, properVolume, 0);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Log.d(TAG," ----> currentVolume = "+currentVolume);
    }

    private Runnable mSnowboyInsance = new Runnable() {
        @Override
        public void run() {
            matrix.everloop.drawProgress(34);
            matrix.everloop.write();
            Log.w(TAG, "=== starting wakeword recognition ===");
            Log.d(TAG, "commonRes:"+commonRes);
            Log.d(TAG, "activeModel:"+activeModel);
            detector = new SnowboyDetect(commonRes, activeModel);
            detector.SetSensitivity("0.6");         // Sensitivity for each hotword
            mAudioRecord.startRecording();
            mAssistantHandler.post(mSnowboyDetectRequest);
        }
    };

    byte[] audioBuffer = new byte[SAMPLE_BLOCK_SIZE];
    long shortsRead = 0;
    private Runnable mSnowboyDetectRequest = new Runnable() {

        @Override
        public void run() {
            mAudioRecord.read(audioBuffer, 0, audioBuffer.length);
            // Converts to short array.
            short[] audioData = new short[audioBuffer.length / 2];
            ByteBuffer.wrap(audioBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(audioData);

            shortsRead += audioData.length;
            // Snowboy hotword detection.
            int result = detector.RunDetection(audioData, audioData.length);
//            Log.e(TAG, "result: "+result);

            if (result == -2) {
                // post a higher CPU usage:
                // sendMessage(MsgEnum.MSG_VAD_NOSPEECH, null);
            } else if (result == -1) {
                Log.e(TAG, "Unknown Detection Error");
            } else if (result == 0) {
                // post a higher CPU usage:
                // sendMessage(MsgEnum.MSG_VAD_SPEECH, null);
            } else if (result > 0) {
                Log.w("== Snowboy: ", "Hotword " + Integer.toString(result) + " detected! ==");
                matrix.everloop.clear();
                matrix.everloop.write();
                mAssistantHandler.post(mStartAssistantRequest);
                return;
            }
            mAssistantHandler.post(mSnowboyDetectRequest);
        }
    };
}
