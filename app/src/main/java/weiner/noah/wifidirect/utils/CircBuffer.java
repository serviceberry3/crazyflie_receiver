package weiner.noah.wifidirect.utils;

import android.util.Log;

public class CircBuffer {
    //volatile: guarantees that writes to buffer happen before updating head, ensures consumer will always see latest val of head.
    private volatile int head, tail;
    private final int max;
    private boolean full;

    //public properties so they can be used by the convolver
    public float[] dataBuffer;
    public long[] timeBuffer;
    public long bufferAddress;

    private final String TAG = "CircBuffer";


    //subclass that defines an entry
    public static class Entry {
        private long timestamp;
        private float data;

        public Entry(float data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }


    //construct an instance of a circular buffer using a design
    public CircBuffer(int size) {
        //hold distance/angle data
        dataBuffer = new float[size];

        //hold timestamps of corresponding data
        timeBuffer = new long[size];
        max = size;
        reset();
    }


    public void reset() {
        head = tail = 0;
        full = false;
    }

    //reset the circular buffer to empty, head == tail
    public void retreatPointer() {
        //buffer no longer full because we're popping off an entry
        full = false;

        //FIXME: make operations on tail atomic
        tail = (tail + 1) % max;
    }

    public void advancePointer() {
        //if the buffer is full (head=tail), we need to throw OUT the the FIRST-IN data by advancing the tail as well (it's a FIFO queue)
        if (full) {
            tail = (tail + 1) % max;
        }

        //advance the head no matter what
        head = (head + 1) % max;

        //check if the advancement made head equal to tail, which means the circular queue is now full
        full = (head == tail); //if it was full before the advance it'll be full after too
    }

    //put version 1 continues to add data if the buffer is full
    //old data is overwritten
    public int put(float data, long timestamp) {
        dataBuffer[head] = data;
        timeBuffer[head] = timestamp;
        advancePointer();
        return head;
    }

    //retrieve a value from the buffer
    //returns 0 on success, -1 if the buffer is empty
    public Entry get() {
        //error code of -10000 is safe for our purposes
        Entry r = null;

        if (!isEmpty()) {
            r = new Entry(dataBuffer[tail], timeBuffer[tail]);
            retreatPointer();
        }

        //return data at tail, otherwise return -10000;
        return r;
    }

    //returns true if the buffer is empty
    public boolean isEmpty() {
        //boolean of negation of full anded with head=tail
        return (!full && (head == tail));
    }

    //returns true if the buffer is full
    public boolean isFull() {
        return full;
    }

    //returns the maximum capacity of the buffer
    public int getCapacity() {
        return max;
    }

    //returns the current number of elements in the buffer
    public int getCurrSize() {
        //if the buffer is full, our size is the max
        int size = max;

        if (!full)
        {
            //if circular buffer is not full and head is greater than tail, find difference to get current size
            if (head >= tail)
            {
                size = head - tail;
            }

            //otherwise we've taken out stuff past the head (which means the buffer was full, so the current size is the maximum minus
            // however much has been taken out (space between head and tail)
            else
            {
                size = (max + head - tail);
            }
        }
        return size;
    }

    //give an average of the last n entries in the buffer
    public float aggregateLastNEntries(int n) {
        //make sure the requested n is not greater than the current population of the queue
        int size = getCurrSize();
        if (n > size) {
            return -1;
        }

        //initialize an average
        float average = 0;

        //find the head of the queue as an integer
        int position = (int) head - 1; //last new data that was added is at head - 1

        int cutoff = position - n;
        if (cutoff < 0) {
            cutoff = (int) max + cutoff;
        }

        //run back n spaces in the queue, adding all entries to the average
        for (int i = position; i != cutoff; i--) {
            //i could become negative if the head was at a low number, so need to correct that
            if (i < 0) {
                //change i to the correct index of the buffer
                i = (int) max + i;
                if (i == cutoff) {
                    return average / (float)n;
                }
            }

            //add absolute value acceleration reading to the average
            average += Math.abs(dataBuffer[i]);
        }

        //divide average by number of elements read to get aggregate reading
        return average / (float)n;
    }

    //get displacement between newest and oldest values in buffer
    public float getDispOverTime() {
        //need at least two entries
        if (getCurrSize() < 2)
            return -100000;

        //initialize an average
        float average = 0;

        //find the head of the queue as an integer
        int newest = (head == 0) ? max - 1 : head - 1;      //last new data that was added is at head - 1

        //if buffer not full, oldest data is found at tail. Otherwise, it's found at head (or tail, since they'll be same). It's the data that's about
        //to be overwritten. So it's always at tail.
        int oldest = tail;

        Log.i(TAG, "Newest is " + newest + ", oldest is " + oldest);


       float disp = dataBuffer[newest] - dataBuffer[oldest];

       float timeElapsed = timeBuffer[newest] - timeBuffer[oldest];

       Log.i(TAG, "Elapsed time over buffer was " + timeElapsed / 1000000000 + "sec");


       //divide by 10^9 to find m/s
       return disp / (timeElapsed / 1000000000);
    }

    //retrieve the head position of the circular buffer
    public int getHead() {
        return head;
    }

    //get address of the buffer to pass to convolver
    public long getAddress() {
        return 0;
    }

}
