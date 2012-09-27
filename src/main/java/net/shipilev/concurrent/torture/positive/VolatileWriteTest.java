package net.shipilev.concurrent.torture.positive;

import net.shipilev.concurrent.torture.Outcome;
import net.shipilev.concurrent.torture.TwoThreadTest;

public class VolatileWriteTest extends TwoThreadTest<VolatileWriteTest.Specimen> {

    public static class Specimen {
        int x;
        volatile int y;
    }

    @Override
    public void thread0(Specimen s) {
        s.x = 1;
        s.x = 2;
        s.y = 1;
        s.x = 3;
    }

    @Override
    public void thread1(Specimen s, byte[] res) {
        res[0] = (byte) s.y;
        res[1] = (byte) s.x;
    }

    @Override
    public Specimen createNew() {
        return new Specimen();
    }

    @Override
    protected Outcome test(byte[] res) {
        if (res[0] == 1 && res[1] < 2) {
            // volatile read happened
            // should always read x == 2 or x == 3
            return Outcome.NOT_EXPECTED;
        }
        return Outcome.ACCEPTABLE;
    }

    public int resultSize() {
        return 2;
    }

}
