package weiner.noah.wifidirect.crtp;

import java.nio.ByteBuffer;

public class PositionPacket extends CrtpPacket {
    private final float mDx;
    private final float mDy;
    private final float mYawrate;
    private final float mHeight;

    /**
     * Create a new position packet.
     *
     * @param dx (m)
     * @param dy (m)
     * @param height (m)
     */
    public PositionPacket(float dx, float dy, float yawRate, float height) {
        //position is Port 9, channel 0
        super(0, CrtpPort.COMMANDER_POSHOLD);

        this.mDx = dx; // * (float)(Math.PI / 180.0);
        this.mDy = dy; // * (float)(Math.PI / 180.0);
        this.mYawrate = yawRate; // * (float)(Math.PI / 180.0);
        this.mHeight = height;
    }

    @Override
    protected void serializeData(ByteBuffer buffer) {
        //type: also 9
        buffer.put((byte) 0x09);

        buffer.putFloat(mDx);
        buffer.putFloat(mDy); //invert axis
        buffer.putFloat(mYawrate);
        buffer.putFloat(mHeight);
    }

    @Override
    protected int getDataByteCount() {
        return 1 + 4 * 4; // 1 byte (type) + 4 floats with size 4 = 17 vs. 14 for CommanderPacket
    }

    @Override
    public String toString() {
        return "positionPacket: dX: " + this.mDx + " dY: " + this.mDy + " yawRate: " +
                this.mYawrate + " zDistance (height): " + this.mHeight;
    }
}
