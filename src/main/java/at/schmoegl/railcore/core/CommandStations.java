package at.schmoegl.railcore.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Verzeichnis aller verfügbaren Zentralen-Typen. Neue Treiber werden entweder hier registriert oder
 * über META-INF/services/at.schmoegl.railcore.core.CommandStationFactory als Plugin geladen.
 */
public final class CommandStations {
    private static final List<CommandStationFactory> FACTORIES = new ArrayList<>();

    static {
        ServiceLoader.load(CommandStationFactory.class).forEach(FACTORIES::add);
    }

    private CommandStations() {}

    public static synchronized void register(CommandStationFactory f) {
        if (find(f.id()).isEmpty()) FACTORIES.add(f);
    }

    public static synchronized List<CommandStationFactory> all() { return Collections.unmodifiableList(new ArrayList<>(FACTORIES)); }

    public static synchronized Optional<CommandStationFactory> find(String id) {
        return FACTORIES.stream().filter(f -> f.id().equalsIgnoreCase(id)).findFirst();
    }
}
