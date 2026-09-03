package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.storage.MetaParser;

import java.util.List;

/**
 * A fully decoded recording, produced off the main thread (IO executor).
 * Handing this to the main thread keeps {@code /er play} tick-safe no matter
 * how large the recording is: session assembly is then only field copies.
 */
public record DecodedRecording(
        List<String> palette,
        int[] blockData,
        int blockSizeX,
        int blockSizeY,
        int blockSizeZ,
        byte[] blockNbt,
        List<TimelineEvent> timeline,
        MetaParser.Parsed meta) {
}
