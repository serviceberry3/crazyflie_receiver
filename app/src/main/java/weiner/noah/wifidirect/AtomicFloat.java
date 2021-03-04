package weiner.noah.wifidirect;

import java.util.concurrent.atomic.AtomicInteger;

public class AtomicFloat extends Number {
        private final AtomicInteger bits;

        //initialize value to 0
        public AtomicFloat() {
            this(0f);
        }

        public AtomicFloat(float initialValue) {
            bits = new AtomicInteger(Float.floatToIntBits(initialValue));
        }

        public final boolean compareAndSet(float expect, float update) {
            return bits.compareAndSet(Float.floatToIntBits(expect),
                    Float.floatToIntBits(update));
        }

        public final void set(float newValue) {
            bits.set(Float.floatToIntBits(newValue));
        }

        public final float get() {
            return Float.intBitsToFloat(bits.get());
        }

        public float floatValue() {
            return get();
        }

        public final float getAndSet(float newValue) {
            return Float.intBitsToFloat(bits.getAndSet(Float.floatToIntBits(newValue)));
        }

        public final boolean weakCompareAndSet(float expect, float update) {
            return bits.weakCompareAndSet(Float.floatToIntBits(expect),
                    Float.floatToIntBits(update));
        }

        public double doubleValue() { return (double) floatValue(); }
        public int intValue()       { return (int) get();           }
        public long longValue()     { return (long) get();          }

}
