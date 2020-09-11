package com.lapissea.cfs.io.content;

import java.io.IOException;

public interface BufferErrorSupplier<T extends Throwable>{
	BufferErrorSupplier<IOException> DEFAULT_WRITE=(written, expected)->new IOException("Written "+written+" but "+expected+" was expected");
	BufferErrorSupplier<IOException> DEFAULT_READ =(read, expected)->new IOException("Read "+read+" but "+expected+" was provided");
	T apply(int written, int expected);
}
