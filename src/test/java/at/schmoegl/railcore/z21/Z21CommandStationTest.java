package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.core.CommandStationListener;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.core.StationCapabilities.Feature;
import at.schmoegl.railcore.util.TimeSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** Z21-Betriebstreiber ohne Netzwerk: Fähigkeiten, Anmeldung, Sendefehler. */
public class Z21CommandStationTest {

    private final TimeSource.Manual clock = new TimeSource.Manual();

    public void testCapabilitiesFromFirmwareNeverAssumed() {
        assertEquals(java.util.Set.of(), Z21CommandStation.capabilitiesFor(0x0129).features());
        StationCapabilities v130 = Z21CommandStation.capabilitiesFor(0x0130);
        assertEquals(true, v130.has(Feature.CAN_DETECTORS));
        assertEquals(true, v130.has(Feature.RAILCOM));
        assertEquals(false, v130.has(Feature.FUNCTIONS_F29_F31));
        StationCapabilities v143 = Z21CommandStation.capabilitiesFor(0x0143);
        assertEquals("1.43", v143.firmware());
        assertEquals(true, v143.has(Feature.FUNCTIONS_F29_F31));
        assertEquals(false, v143.has(Feature.LOCO_BROADCAST));       // an der Z21 1.43 nicht gegeben (Prüfprogramm)
        assertEquals(false, StationCapabilities.UNKNOWN.detected());
        assertEquals(true, StationCapabilities.UNKNOWN.features().isEmpty());
    }

    public void testLoginWithoutRBusAndConnectedOnlyAfterReply() throws IOException {
        SimulatedZ21Link z21 = new SimulatedZ21Link(clock);
        Z21CommandStation st = new Z21CommandStation("Z21", z21.factory(), clock, 150, 1000, 3000, false);
        List<String> events = new ArrayList<>();
        st.addListener(new CommandStationListener() {
            @Override public void onConnectionChanged(boolean connected, String info) { events.add("verbunden " + connected); }
            @Override public void onCapabilities(StationCapabilities c) { events.add("fähig " + c.firmware()); }
        });
        st.connect();
        assertEquals(false, st.isConnected());
        assertEquals(List.of(), events);
        assertEquals(1L, z21.count("04 00 10 00"));                              // Seriennummer
        assertEquals(1L, z21.count("07 00 40 00 F1 0A FB"));                     // Firmware
        assertEquals(1L, z21.count(Z21Protocol.hex(Z21Protocol.setBroadcastFlags(Z21Protocol.BC_DRIVING_SWITCHING))));
        clock.advanceMillis(5);
        while (st.service(0)) { /* abarbeiten */ }
        assertEquals(true, st.isConnected());
        assertEquals(List.of("verbunden true", "fähig 1.43"), events);
        assertEquals(0L, z21.countPrefix("05 00 81 00"));                        // kein R-Bus
        assertEquals(0L, z21.broadcastFlags & Z21Protocol.BC_RBUS);
        st.disconnect();
        assertEquals(List.of("verbunden true", "fähig 1.43", "verbunden false"), events);
    }

    public void testSendFailureIsNeverReportedAsSent() throws IOException {
        boolean[] fail = {false};
        Z21Link failing = new Z21Link() {
            @Override public void send(byte[] packet) throws IOException { if (fail[0]) throw new IOException("Netz weg (Test)"); }
            @Override public byte[] receive(long timeoutMillis) { return null; }
            @Override public void close() { }
        };
        Z21CommandStation st = new Z21CommandStation("Z21", () -> failing, clock, 150, 1000, 3000, false);
        List<String> errors = new ArrayList<>();
        st.addListener(new CommandStationListener() {
            @Override public void onError(String message, Throwable cause) { errors.add(message); }
        });
        st.connect();
        fail[0] = true;
        assertThrows(UncheckedIOException.class, st::emergencyStop);         // Nothalt nicht still verschluckt
        assertEquals(1, errors.size());
        st.disconnect();
        assertThrows(IllegalStateException.class, st::emergencyStop);        // getrennt: nichts gilt als gesendet
    }
}
