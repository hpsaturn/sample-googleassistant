package admobilize.matrix.io;

import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Created by Antonio Vanegas @hpsaturn on 12/20/16.
 */

public class MicArray extends SensorBase {

    private static final String TAG = MicArray.class.getSimpleName();
    private static final boolean DEBUG = Config.DEBUG;

    private boolean inRead;

    private byte[] data = new byte[128*8*2];
    private ArrayDeque<Short> mic0 = new ArrayDeque<>();
    private ArrayDeque<Short> mic1 = new ArrayDeque<>();
    private ArrayDeque<Short> mic2 = new ArrayDeque<>();
    private ArrayDeque<Short> mic3 = new ArrayDeque<>();
    private ArrayDeque<Short> mic4 = new ArrayDeque<>();
    private ArrayDeque<Short> mic5 = new ArrayDeque<>();
    private ArrayDeque<Short> mic6 = new ArrayDeque<>();
    private ArrayDeque<Short> mic7 = new ArrayDeque<>();

    private ArrayList<ArrayDeque> micarray=new ArrayList<>();
    private Gpio gpio;
    private boolean isReadyData;

    public MicArray(Wishbone wb) {
        super(wb);
        if(Config.MATRIX_CREATOR) {
            micarray.add(mic3);  // Order for MEMs position on the board
            micarray.add(mic4);
            micarray.add(mic5);
            micarray.add(mic6);
            micarray.add(mic7);
            micarray.add(mic0);
            micarray.add(mic1);
            micarray.add(mic2);
        }else{
            micarray.add(mic0);  // Order for MEMs position on the MATRIX Voice board.
            micarray.add(mic1);
            micarray.add(mic2);
            micarray.add(mic3);
            micarray.add(mic4);
            micarray.add(mic5);
            micarray.add(mic6);
            micarray.add(mic7);
        }
        configMicDataInterrupt();
    }

    private void configMicDataInterrupt(){
        try {
            PeripheralManagerService service = new PeripheralManagerService();
            gpio = service.openGpio(BoardDefaults.getGPIO_MIC_DATA());
            gpio.setDirection(Gpio.DIRECTION_IN);
            gpio.setActiveType(Gpio.ACTIVE_LOW);
            // Register for all state changes
            gpio.setEdgeTriggerType(Gpio.EDGE_BOTH);
            gpio.registerGpioCallback(onMicDataCallback);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private GpioCallback onMicDataCallback = new GpioCallback() {
        @Override
        public boolean onGpioEdge(Gpio gpio) {
            read();
            return super.onGpioEdge(gpio);
        }
        @Override
        public void onGpioError(Gpio gpio, int error) {
            super.onGpioError(gpio, error);
            Log.w(TAG, "[MIC] onMicDataCallback error event: "+gpio + "==>" + error);
        }
    };

    private void read(){
        if(inRead==false) {
            inRead = true;
            wb.SpiReadBurst((short) kMicrophoneArrayBaseAddress, data, 128 * 8 * 2);
            appendData();
            inRead = false;
        }else
            Log.w(TAG,"[MIC] skip read data!");
    }

    public int read(ByteBuffer byteBuffer, int i) throws IOException {
        if(isReadyData) {
            int oldpos = byteBuffer.position();
            for (int x = 0; x < (i / 2); x++) {
                byteBuffer.putShort(mic0.poll());
            }
            int newpos = byteBuffer.position();
            Log.d(TAG, "[MIC] byteBuffer bytes read: "+(newpos-oldpos));
            return newpos - oldpos;
        }
        return 0;
    }

    private void appendData(){
        isReadyData=false;
        for (int i=0;i<128;i++){
            // TODO: implement all mics (see possible memory leak)
            mic0.add(ByteBuffer.wrap(data,(i*8+0)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic1.add(ByteBuffer.wrap(data,(i*8+1)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic2.add(ByteBuffer.wrap(data,(i*8+2)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic3.add(ByteBuffer.wrap(data,(i*8+3)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic4.add(ByteBuffer.wrap(data,(i*8+4)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic5.add(ByteBuffer.wrap(data,(i*8+5)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic6.add(ByteBuffer.wrap(data,(i*8+6)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic7.add(ByteBuffer.wrap(data,(i*8+7)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
        }
        isReadyData=true;
    }

}
