package com.lapissea.dfs.io;

import java.io.IOException;
import java.util.stream.LongStream;

public interface IOHook{
	void writeEvent(IOInterface data, LongStream changeIds) throws IOException;
}
