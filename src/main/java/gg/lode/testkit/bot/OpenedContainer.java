package gg.lode.testkit.bot;

import java.util.List;

/**
 * A container the server opened for a fake player.
 *
 * @param id    window id
 * @param type  container type, e.g. {@code GENERIC_9X3}
 * @param title plain-text title
 * @param slots one entry per slot of the container (player inventory excluded): the item's custom name if it
 *              has one, else its item id, or null for an empty slot
 */
public record OpenedContainer(int id, String type, String title, List<String> slots) {

    public long filledSlots() {
        return slots.stream().filter(s -> s != null).count();
    }

    /** First slot whose name contains the text, or -1. */
    public int slotNamed(String text) {
        for (int i = 0; i < slots.size(); i++) {
            String s = slots.get(i);
            if (s != null && s.contains(text)) return i;
        }
        return -1;
    }
}
