package at.schmoegl.bahnsteuerung.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Gemeinsame Listener-Verwaltung für Zentralen-Treiber. */
public abstract class AbstractCommandStation implements CommandStation {

    private final List<CommandStationListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void addListener(CommandStationListener l) { listeners.add(l); }

    @Override
    public void removeListener(CommandStationListener l) { listeners.remove(l); }

    protected void fire(Consumer<CommandStationListener> event) {
        for (CommandStationListener l : listeners) {
            try {
                event.accept(l);
            } catch (RuntimeException e) {
                System.err.println("Fehler in Listener: " + e);
                e.printStackTrace();
            }
        }
    }

    protected void fireError(String message, Throwable cause) { fire(l -> l.onError(message, cause)); }
}
