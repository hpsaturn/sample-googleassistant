package admobilize.matrix.io;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.util.Log;

import com.google.android.things.pio.PeripheralManagerService;
import com.google.android.things.pio.SpiDevice;
import com.google.android.things.userdriver.AudioInputDriver;
import com.google.android.things.userdriver.UserDriverManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;


/**
 * Created by Antonio Vanegas @hpsaturn on 9/9/17.
 */

public class MatrixDriver implements AutoCloseable {

    private static final String TAG = MatrixDriver.class.getSimpleName();

    private static final boolean DEBUG = Config.DEBUG;
    private static final int BUFFER_MATRIX = 256;

    private AudioInputUserDriver mAudioInputDriver;

    // Audio constants.
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private SpiDevice spiDevice;
    public Everloop everloop;
    public MicArray micArray;


    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    public MatrixDriver() {
        PeripheralManagerService service = new PeripheralManagerService();
        while (!configSPI(service)) Log.i(TAG, "waiting for SPI..");
        initDevices();
    }

    private boolean configSPI(PeripheralManagerService service){
        try {
            List<String> deviceList = service.getSpiBusList();
            if (deviceList.isEmpty()) {
                Log.i(TAG, "No SPI bus available");
            } else {
                Log.i(TAG, "List of available devices: " + deviceList);
                spiDevice = service.openSpiDevice(BoardDefaults.getSpiBus());
                spiDevice.setMode(SpiDevice.MODE3);
                spiDevice.setFrequency(18000000);     // 18MHz
                spiDevice.setBitsPerWord(8);          // 8 BP
                spiDevice.setBitJustification(false); // MSB first
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error on PeripheralIO API (SPI)..");
        }

        return false;
    }

    private void initDevices() {
        // TODO: autodetection of hat via SPI register
        Wishbone wb = new Wishbone(spiDevice);
        everloop = new Everloop(wb); // NOTE: please change to right board on Config class
        everloop.drawProgress(34);
        everloop.write();

        micArray = new MicArray(wb);
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
                return micArray.readFromDevice(byteBuffer, i);
            } catch (IOException e) {
                Log.e(TAG, "[MIC] error during readFromDevice operation:", e);
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
