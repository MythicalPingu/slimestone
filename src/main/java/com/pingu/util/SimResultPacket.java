package com.pingu.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record SimResultPacket(List<EventEntry> entries) implements CustomPacketPayload {

    public static final ResourceLocation ID_LOC = ResourceLocation.fromNamespaceAndPath("slimestone", "sim_result");
    public static final Type<SimResultPacket> TYPE = new Type<>(ID_LOC);

    public static final StreamCodec<FriendlyByteBuf, SimResultPacket> CODEC = StreamCodec.of(
            SimResultPacket::encode,
            SimResultPacket::decode
    );

    public enum Result { SUCCESS_PULLED, SUCCESS_NO_PULL, FAILED_BLOCKED, SUCCESS_PUSHED, FAILED_PUSH_BLOCKED, FAILED_PUSH_NO_PISTON }

    public record EventEntry(BlockPos pos, Direction facing, Result result, int tick, List<String> changes) {}

    private static void encode(FriendlyByteBuf buf, SimResultPacket pkt) {
        buf.writeInt(pkt.entries().size());
        for (EventEntry e : pkt.entries()) {
            buf.writeLong(e.pos().asLong());
            buf.writeByte(e.facing().ordinal());
            buf.writeByte(e.result().ordinal());
            buf.writeInt(e.tick());

            buf.writeInt(e.changes().size());
            for (String change : e.changes()) buf.writeUtf(change);
        }
    }

    private static SimResultPacket decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        List<EventEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(buf.readLong());
            Direction facing = Direction.values()[buf.readByte()];
            Result result = Result.values()[buf.readByte()];
            int tick = buf.readInt();

            int changeCount = buf.readInt();
            List<String> changes = new ArrayList<>(changeCount);
            for (int j = 0; j < changeCount; j++) changes.add(buf.readUtf());

            entries.add(new EventEntry(pos, facing, result, tick, changes));
        }
        return new SimResultPacket(entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}