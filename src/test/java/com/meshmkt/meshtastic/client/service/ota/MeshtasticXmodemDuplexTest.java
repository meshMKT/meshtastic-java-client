package com.meshmkt.meshtastic.client.service.ota;

import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.support.FakeTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.XmodemProtos;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link MeshtasticXmodemDuplex} bridges strategy-level duplex calls into
 * client-level XMODEM primitives.
 */
class MeshtasticXmodemDuplexTest {

    private MeshtasticClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * Verifies duplex send/receive behavior through a connected fake transport.
     */
    @Test
    void duplexBridgesSendAndControlAwait() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);

        // Complete startup sync so the client is in normal READY mode.
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(6060).build())
                .build().toByteArray());
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());

        MeshtasticXmodemDuplex duplex = client.createXmodemDuplex();
        duplex.reset();

        XmodemProtos.XModem outbound = XmodemProtos.XModem.newBuilder()
                .setControl(XmodemProtos.XModem.Control.EOT)
                .build();
        duplex.send(outbound).get(2, TimeUnit.SECONDS);

        var writes = transport.getWritesSnapshot();
        MeshProtos.ToRadio written = MeshProtos.ToRadio.parseFrom(writes.get(writes.size() - 1));
        assertEquals(XmodemProtos.XModem.Control.EOT, written.getXmodemPacket().getControl());

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setXmodemPacket(XmodemProtos.XModem.newBuilder().setControl(XmodemProtos.XModem.Control.ACK).build())
                .build().toByteArray());

        assertEquals(XmodemProtos.XModem.Control.ACK, duplex.nextControl(Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS));
    }
}
