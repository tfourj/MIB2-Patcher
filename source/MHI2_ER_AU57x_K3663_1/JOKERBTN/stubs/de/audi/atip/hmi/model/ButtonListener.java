package de.audi.atip.hmi.model;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface ButtonListener {
    void keyPressed(int modelId, int arg1, int arg2);

    void keyReleased(int modelId, int arg1, int arg2);

    void keyTyped(int modelId, int arg1, int arg2);
}
