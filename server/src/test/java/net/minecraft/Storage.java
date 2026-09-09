package net.minecraft;

public class Storage {
    public int total(Chest chest, Barrel barrel) {
        return chest.size() + barrel.size();
    }

    public int stackOf(BlockItem blockItem) {
        return blockItem.getMaxStackSize();
    }

    public int raw(BlockItem blockItem) {
        return blockItem.maxStackSize;
    }
}
