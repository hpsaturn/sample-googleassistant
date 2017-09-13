package admobilize.matrix.io;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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

    // buffer of 0.05 sec of sample data at 48khz / 16bit.
    private static final int BUFFER_SIZE = 96000 / 20;
    // buffer of 0.5 sec of sample data at 48khz / 16bit.
    private static final int FLUSH_SIZE = 48000;
    private static final int BUFFER_MATRIX = 640;
    private static final int SAMPLE_BLOCK_SIZE = 128;

    private MicArray micArray;
    private I2sDevice mDevice;
    private Gpio mTriggerGpio;
    private AudioInputUserDriver mAudioInputDriver;

    // Audio constants.
    private static final String PREF_CURRENT_VOLUME = "current_volume";
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int DEFAULT_VOLUME = 100;

    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;

    public MicArrayDriver(MicArray micArray, AudioFormat audioFormatInMono) {
        this.micArray=micArray;
    }

    @Override
    public void close() throws Exception {
        unregisterAudioInputDriver();
    }

    private class AudioInputUserDriver extends AudioInputDriver {
        @Override
        public void onStandbyChanged(boolean b) {
        }

        @Override
        public int read(ByteBuffer byteBuffer, int i) {
            try {
//                return mDevice.read(byteBuffer, i);
                return micArray.read(byteBuffer, i);
            } catch (IOException e) {
                Log.e(TAG, "[MIC] error during read operation:", e);
                return -1;
            }
        }
    }

    public void registerAudioInputDriver() {
        mAudioInputDriver = new AudioInputUserDriver();
        UserDriverManager.getManager().registerAudioInputDriver(mAudioInputDriver, AUDIO_FORMAT_IN_MONO,
                AudioDeviceInfo.TYPE_BUILTIN_MIC, BUFFER_MATRIX);
    }

    public void unregisterAudioInputDriver() {
        if (mAudioInputDriver != null) {
            UserDriverManager.getManager().unregisterAudioInputDriver(mAudioInputDriver);
            mAudioInputDriver = null;
        }
    }

}
