package turfwar.server;

/** step 10 
 * Round clock use System.nanoTime(), never jumps when the computer adjusts its own clock.
 * The same clock gives replay events their time offsets. 
 */
public class RoundClock {
    private long totalTime;
    private long remainingTime;
    private long resumedTime;
    private boolean running;
    
    // start a new round of the given length
    public void start(int seconds) {
        totalTime = seconds * 1_000_000_000L;
        remainingTime = totalTime;
        resumedTime = System.nanoTime();
        running = true;
    }

    // freeze the time that is left 
    public void pause() {
        if (!running) {
            return; 
        }
        remainingTime = currentRemaining();
        running = false; 
    }

    // start counting down again from the frozen value
    public void resume() {
        if (running) {
            return;
        }
        resumedTime = System.nanoTime();
        running = true; 
    }

    // round over or match closed: freeze where it is, never below zero
    public void stop() { 
        remainingTime = Math.max(0, currentRemaining());
        running = false;
    }

    // nanoseconds left right now
    private long currentRemaining() {
        if (running) {
            return remainingTime - (System.nanoTime() - resumedTime);
        }
        return remainingTime;
    }
    // check if the clock reached zero 
    public boolean expired() {
        return currentRemaining() <= 0;
    }
    // displays whole seconds, rounded up, never negative
    public int remainingSeconds() {
        long n = Math.max(0, currentRemaining());
        return (int) ((n + 999_999_999L) / 1_000_000_000L);
    }
    // milliseconds the round has run, pauses excluded to use as replay offsets
    public long elapsedMs() {
        long passed = totalTime - Math.max(0, currentRemaining());
        return Math.max(0, Math.min(passed, totalTime)) / 1_000_000L;
    }

}
