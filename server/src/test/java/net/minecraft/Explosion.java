package net.minecraft;

public class Explosion {
    public enum InteractionType {
        NONE, BLOCK, ENTITY
    }

    public InteractionType getInteraction() {
        return InteractionType.BLOCK;
    }
}
