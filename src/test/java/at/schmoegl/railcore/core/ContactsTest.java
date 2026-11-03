package at.schmoegl.railcore.core;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** Kontaktnummer = (Modul − 1) · 8 + Eingang, z. B. RM017/1 = 129 (siehe FEATURES.md). */
public class ContactsTest {

    public void testOfModuleAndInput() {
        assertEquals(1, Contacts.of(1, 1));
        assertEquals(9, Contacts.of(2, 1));
        assertEquals(129, Contacts.of(17, 1));
        assertEquals(144, Contacts.of(18, 8));
    }

    public void testRoundTrip() {
        for (int c = 1; c <= 144; c++) {
            assertEquals(c, Contacts.of(Contacts.module(c), Contacts.input(c)));
            assertEquals(c, Contacts.parse(Contacts.format(c)));
        }
    }

    public void testParse() {
        assertEquals(12, Contacts.parse(" 12 "));
        assertEquals(12, Contacts.parse("2:4"));
    }

    public void testInvalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> Contacts.of(0, 1));
        assertThrows(IllegalArgumentException.class, () -> Contacts.of(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Contacts.of(1, 9));
    }
}
