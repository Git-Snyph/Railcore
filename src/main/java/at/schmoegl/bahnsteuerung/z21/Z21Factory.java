package at.schmoegl.bahnsteuerung.z21;

import at.schmoegl.bahnsteuerung.core.CommandStation;
import at.schmoegl.bahnsteuerung.core.CommandStationFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class Z21Factory implements CommandStationFactory {

    @Override public String id() { return "z21"; }

    @Override public String displayName() { return "Roco/Fleischmann Z21"; }

    @Override
    public Map<String, String> defaultSettings() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("host", "192.168.0.111");
        m.put("port", String.valueOf(Z21Protocol.PORT));
        m.put("turnoutPulseMs", "150");
        m.put("pollMs", "1000");
        return m;
    }

    @Override
    public CommandStation create(Map<String, String> s) {
        return new Z21CommandStation(s.getOrDefault("host", "192.168.0.111"),
                Integer.parseInt(s.getOrDefault("port", String.valueOf(Z21Protocol.PORT))),
                Integer.parseInt(s.getOrDefault("turnoutPulseMs", "150")),
                Integer.parseInt(s.getOrDefault("pollMs", "1000")));
    }
}
