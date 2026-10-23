package net.minecraft;

// References a member class of another class: a decompile names it InteractionType (imported) when
// Explosion is only a library, and Explosion.InteractionType when both are inputs of one run.
public class Blast {
    public Explosion.InteractionType kind(Explosion explosion) {
        return explosion.getInteraction();
    }
}
