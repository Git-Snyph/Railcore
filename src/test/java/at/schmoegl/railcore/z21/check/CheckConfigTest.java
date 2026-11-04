package at.schmoegl.railcore.z21.check;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

public class CheckConfigTest {

    public void testDefaultsArePassive() {
        CheckConfig c = CheckConfig.defaults();
        assertEquals(CheckConfig.Mode.PASSIVE, c.mode());
        assertEquals(9001, c.proxyAddress());
        assertEquals(1, c.driveSpeedStep());
        assertEquals(1, c.turnoutAddress());
        assertEquals(150, c.turnoutPulseMs());
        assertEquals(0, c.pomDecoderAddress());
    }

    public void testParse() {
        CheckConfig c = CheckConfig.parse(new String[]{"--host", "10.0.0.5", "--stufe", "aktiv", "--dauer", "60",
                "--weiche", "5", "--stellvertreter", "9999", "--fahrstufe", "10"});
        assertEquals("10.0.0.5", c.host());
        assertEquals(CheckConfig.Mode.ACTIVE, c.mode());
        assertEquals(60, c.observeSeconds());
        assertEquals(1, c.pomDecoderAddress());
        assertEquals(9999, c.proxyAddress());
        assertEquals(10, c.driveSpeedStep());
    }

    public void testProxyAddressMustBeInReservedRange() {
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--stellvertreter", "8999"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--stellvertreter", "10000"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--stellvertreter", "3"}));
    }

    public void testSpeedStepIsCapped() {
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--fahrstufe", "11"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--fahrstufe", "0"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--fahrzeit-ms", "60000"}));
    }

    public void testInvalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--unbekannt", "1"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--stufe"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--stufe", "voll"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--dauer", "x"}));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.parse(new String[]{"--verbindung-ms", "10", "--antwort-ms", "1000"}));
        assertEquals(true, CheckConfig.wantsHelp(new String[]{"--hilfe"}));
    }
}
