package net.minecraft;

public class Dog extends Animal implements Runnable {
    @Override
    public void run() {
        Animal.staticSound();
    }
}
