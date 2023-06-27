package com.lapissea.cfs.io;

import java.io.IOException;
import java.util.stream.LongStream;

public interface IOHook{
	void writeEvent(IOInterface data, LongStream changeIds) throws IOException;
}
