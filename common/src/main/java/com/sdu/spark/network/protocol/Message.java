package com.sdu.spark.network.protocol;

import com.sdu.spark.network.buffer.ManagedBuffer;
import io.netty.buffer.ByteBuf;

/**
 * @author hanhan.zhang
 * */
public interface Message extends Encodable {

    /**
     * 消息类型
     * */
    Type type();

    /**
     * 消息体
     * */
    ManagedBuffer body();

    boolean isBodyInFrame();

    enum Type implements Encodable {
        RpcRequest(3), RpcResponse(4), RpcFailure(5);

        private final byte id;

        Type(int id) {
            assert id < 128 : "Cannot have more than 128 message types";
            this.id = (byte) id;
        }

        @Override
        public int encodedLength() {
            return 1;
        }

        @Override
        public void encode(ByteBuf buf) {
            buf.writeByte(id);
        }

        public static Type decode(ByteBuf buf) {
            byte id = buf.readByte();
            switch (id) {
                case 3:
                    return RpcRequest;
                case 4:
                    return RpcResponse;
                case 5:
                    return RpcFailure;
                default:
                    throw new IllegalArgumentException("Unknown message type: " + id);
            }
        }
    }
}
