package admobilize.matrix.io;

import android.os.AsyncTask;
import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;


/**
 * Created by Antonio Vanegas @hpsaturn on 12/20/16.
 */

public class MicArray extends SensorBase {

    private static final String TAG = MicArray.class.getSimpleName();
    private static final boolean DEBUG = Config.DEBUG;
    private final ByteBuffer buffer;

    private int current_mic =0;
    private int max_irq_samples;
    private int irq_samples;
    private boolean inRead;

    private byte[] data = new byte[128*8*2];
    private ArrayDeque<Byte> mic0 = new ArrayDeque<>();
    private ArrayDeque<Short> mic1 = new ArrayDeque<>();
    private ArrayDeque<Short> mic2 = new ArrayDeque<>();
    private ArrayDeque<Short> mic3 = new ArrayDeque<>();
    private ArrayDeque<Short> mic4 = new ArrayDeque<>();
    private ArrayDeque<Short> mic5 = new ArrayDeque<>();
    private ArrayDeque<Short> mic6 = new ArrayDeque<>();
    private ArrayDeque<Short> mic7 = new ArrayDeque<>();

    private ArrayList<ArrayDeque> micarray=new ArrayList<>();
    private Gpio gpio;
    private OnMicArrayListener listener;
    private boolean continuous;
    private boolean isBufferReady;

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
        buffer=ByteBuffer.allocateDirect(640);
        configMicDataInterrupt();
    }


    public interface OnMicArrayListener{
        void onCapture(int mic, ArrayDeque<Short> mic_data);
        void onCaptureAll(ArrayList<ArrayDeque> mic_array);
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
            inRead = false;
//            appendData();
        }else
            Log.w(TAG,"[MIC] skip read data!");
    }

    public int read(ByteBuffer byteBuffer, int i) throws IOException {
        if(!inRead) {
            for(int x=0;x<128;x++){
                byteBuffer.putChar(ByteBuffer.wrap(data,(x*8+0)*2,2).order(ByteOrder.BIG_ENDIAN).getChar());
            }
            inRead=false;
            return 256;
        }
//        String data = "";
//        for (int x=0;x<byteBuffer.capacity();x++){
//            data=data+byteBuffer.get(x);
//        }
//        Log.d(TAG, "[MIC] byteBuffer data: "+data);

        return 0;
    }

    private void appendData(){
        for (int i=0;i<128;i++){
            if(mic0.size()==640){
                isBufferReady=false;
                buffer.clear();
                for (Byte aMic0 : mic0) buffer.put(aMic0);
                isBufferReady=true;
                mic0.poll();
            }
            mic0.add(ByteBuffer.wrap(data,(i*8+0)*2,2).order(ByteOrder.BIG_ENDIAN).get());
//            mic1.add(ByteBuffer.wrap(data,(i*8+1)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic2.add(ByteBuffer.wrap(data,(i*8+2)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic3.add(ByteBuffer.wrap(data,(i*8+3)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic4.add(ByteBuffer.wrap(data,(i*8+4)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic5.add(ByteBuffer.wrap(data,(i*8+5)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic6.add(ByteBuffer.wrap(data,(i*8+6)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
//            mic7.add(ByteBuffer.wrap(data,(i*8+7)*2,2).order(ByteOrder.BIG_ENDIAN).getShort());
        }
    }


}
