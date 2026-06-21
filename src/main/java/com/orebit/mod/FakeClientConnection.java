package com.orebit.mod;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

public class FakeClientConnection extends Connection {
    public FakeClientConnection() {
        super(PacketFlow.SERVERBOUND);
    }
}
