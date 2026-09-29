package net.camacraft.grappleunbound.client;

import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.client.settings.KeyConflictContext;

public enum CustomKeyConflicts implements IKeyConflictContext {
    INGAME_NO_CONFLICT{
        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            // Treat this context as conflicting with IN_GAME so KeyModifier.NONE remains active
            // even when Shift/Ctrl/Alt are held during in-game input.
            return other == KeyConflictContext.IN_GAME || other == this;
        }
    };
}
