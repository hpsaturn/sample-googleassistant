package admobilize.matrix.io;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.I2sDevice;
import com.google.android.things.userdriver.AudioInputDriver;
import com.google.android.things.userdriver.UserDriverManager;

import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * Created by Antonio Vanegas @hpsaturn on 9/9/17.
 */

public class MicArrayDriver implements AutoCloseable {

    private static final String TAG = MicArrayDriver.class.getSimpleName();
    private static final boolean DEBUG = Config.DEBUG;

    private final AudioFormat audioFormat;

    private MicArray micArray;
    private AudioInputUserDriver mAudioInputDriver;

    // Audio constants.
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private int inputBuffer;

    public static AudioFormat getAudioFormatInMono() {
        return AUDIO_FORMAT_IN_MONO;
    }

    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    public MicArrayDriver(MicArray micArray, int inputBuffer, AudioFormat audioFormat) {
        this.micArray=micArray;
        this.inputBuffer=inputBuffer;
        this.audioFormat=audioFormat;
    }

    @Override
    public void close() throws Exception {
        unregisterAudioInputDriver();
    }

    private class AudioInputUserDriver extends AudioInputDriver {
        @Override
        public void onStandbyChanged(boolean b) {
            Log.d(TAG, "audio input driver standby changed:" + b);
        }

        @Override
        public int read(ByteBuffer byteBuffer, int i) {
            try {
                Log.i(TAG, "[MIC] reading..");
                return micArray.read(byteBuffer, i);
            } catch (IOException e) {
                Log.e(TAG, "[MIC] error during read operation:", e);
                return -1;
            }
        }
    }

    public void registerAudioInputDriver() {
        mAudioInputDriver = new AudioInputUserDriver();
        UserDriverManager.getManager().registerAudioInputDriver(mAudioInputDriver, audioFormat,
                AudioDeviceInfo.TYPE_BUILTIN_MIC, inputBuffer);
    }

    public void unregisterAudioInputDriver() {
        if (mAudioInputDriver != null) {
            UserDriverManager.getManager().unregisterAudioInputDriver(mAudioInputDriver);
            mAudioInputDriver = null;
        }
    }

}
